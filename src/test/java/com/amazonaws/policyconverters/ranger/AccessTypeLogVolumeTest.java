package com.amazonaws.policyconverters.ranger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.PassthroughPrincipalMapper;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the operator-facing log volume and level for unmappable access types.
 *
 * <p><b>The volume problem.</b> An adapter's {@code mapAccessTypeToCedarActions} is invoked once per
 * access type, per principal, per resource combination — and twice per invocation, because the
 * resource-level overload delegates to the one-arg form. Logging at an operator-facing level inside
 * the adapter therefore produced dozens of identical lines for a single policy: 28 identical
 * "Unknown Hive access type: 'refresh'" lines came out of the unit suite alone. The adapters now
 * trace each occurrence at DEBUG, and {@link RangerToCedarConverter} emits one operator-facing line
 * per policy from the same per-policy accumulator that produces the gap entry, so the log and the
 * gap cannot drift apart.
 *
 * <p><b>The level distinction, which matters more than the volume.</b> An access type an adapter
 * <em>declares</em> unmappable (Hive {@code index}, {@code lock}, {@code refresh}, …) is a known,
 * safe drop and must stay quiet. An access type nothing recognises is a gap in our mapping and must
 * stay loud — in particular because the sync server runs without the assessment tool, so the log is
 * the only place an operator would ever learn about it.
 *
 * <p><b>Deliberately unchanged:</b> the {@code recordUnmappedAccessType} CloudWatch metric remains
 * per-occurrence. It is a Count metric; deduplicating it would silently change its meaning for
 * anyone alarming on it. That is a separate decision from log volume.
 */
class AccessTypeLogVolumeTest {

    private static final AwsContext CTX =
            new AwsContext("us-east-1", "123456789012", "123456789012");

    private ListAppender<ILoggingEvent> appender;
    private Logger converterLogger;
    private Logger adapterLogger;
    private Level originalAdapterLevel;
    private RangerToCedarConverter converter;

    @BeforeEach
    void setUp() {
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("hive", new HiveServiceAdapter(CTX));
        converter = new RangerToCedarConverter(registry, new PassthroughPrincipalMapper(),
                new PassthroughCatalogResolver(), new GapReporter(), new CedarSchemaProvider());

        appender = new ListAppender<>();
        appender.start();

        converterLogger = (Logger) LoggerFactory.getLogger(RangerToCedarConverter.class);
        adapterLogger = (Logger) LoggerFactory.getLogger(HiveServiceAdapter.class);
        originalAdapterLevel = adapterLogger.getLevel();
        // Capture the adapter's DEBUG output too, so the test can show the per-occurrence detail
        // was demoted rather than deleted.
        adapterLogger.setLevel(Level.DEBUG);
        converterLogger.addAppender(appender);
        adapterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        converterLogger.detachAppender(appender);
        adapterLogger.detachAppender(appender);
        adapterLogger.setLevel(originalAdapterLevel);
        appender.stop();
    }

    private long count(Level level, String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .count();
    }

    /**
     * One policy fanning out to nine (principal, resource) pairs: three principals across three
     * tables. Under the old adapter-level logging this shape produced ~18 identical lines.
     */
    private static RangerPolicy fanOutPolicy(String extraAccessType) {
        return fanOutPolicy(4242L, extraAccessType);
    }

    private static RangerPolicy fanOutPolicy(long id, String extraAccessType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("fan-out-" + id);
        policy.setService("hive");
        policy.setIsEnabled(true);
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource db = new RangerPolicyResource();
        db.setValues(List.of("sales"));
        resources.put("database", db);
        RangerPolicyResource tables = new RangerPolicyResource();
        tables.setValues(List.of("orders", "events", "returns"));
        resources.put("table", tables);
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice", "bob", "carol"));
        List<RangerPolicyItemAccess> accesses = new ArrayList<>();
        for (String type : List.of("select", extraAccessType)) {
            RangerPolicyItemAccess access = new RangerPolicyItemAccess();
            access.setType(type);
            access.setIsAllowed(true);
            accesses.add(access);
        }
        item.setAccesses(accesses);
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    @Test
    void unrecognizedAccessTypeLogsExactlyOneErrorPerCycle() {
        converter.convert(List.of(fanOutPolicy("brand_new_privilege")));

        assertEquals(1, count(Level.ERROR, "brand_new_privilege"),
                "an unrecognised access type must produce exactly one ERROR, not one per "
                        + "principal x resource. Events: " + appender.list);
    }

    @Test
    void manyAffectedPoliciesStillLogOneErrorForTheWholeCycle() {
        // The reason this is aggregated per cycle rather than per policy: the sync server
        // re-converts every policy on every refresh (no version short-circuit), so an estate with
        // 169 affected policies would emit ~487k identical ERROR lines a day at the 30s default.
        List<RangerPolicy> policies = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            policies.add(fanOutPolicy(id, "brand_new_privilege"));
        }

        converter.convert(policies);

        assertEquals(1, count(Level.ERROR, "brand_new_privilege"),
                "20 affected policies must still produce one ERROR for the cycle");
    }

    @Test
    void theAggregatedErrorReportsCountsAndSamplePolicyIds() {
        List<RangerPolicy> policies = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            policies.add(fanOutPolicy(id, "brand_new_privilege"));
        }

        converter.convert(policies);

        String msg = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst().orElseThrow();

        assertTrue(msg.contains("20 policies"), "must report the exact affected-policy count: " + msg);
        assertTrue(msg.contains("hive:brand_new_privilege"),
                "must name service and access type: " + msg);
        assertTrue(msg.contains("e.g."), "must name sample policy ids: " + msg);
    }

    @Test
    void distinctUnrecognizedTypesAreTalliedSeparately() {
        converter.convert(List.of(
                fanOutPolicy(1L, "brand_new_privilege"),
                fanOutPolicy(2L, "another_new_privilege")));

        assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count(),
                "still one line for the cycle");
        String msg = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst().orElseThrow();
        assertTrue(msg.contains("2 unrecognised access type"), "must count both types: " + msg);
        assertTrue(msg.contains("brand_new_privilege") && msg.contains("another_new_privilege"),
                "must name both types: " + msg);
    }

    @Test
    void repeatedConvertCallsEachLogAfresh() {
        // The tally must be cycle-scoped, not an instance field: the sync server reuses one
        // converter for the life of the process, so a field would make the signal vanish after
        // the first cycle and never return.
        converter.convert(List.of(fanOutPolicy("brand_new_privilege")));
        converter.convert(List.of(fanOutPolicy("brand_new_privilege")));

        assertEquals(2, count(Level.ERROR, "brand_new_privilege"),
                "each conversion cycle must report the problem again");
    }

    @Test
    void perOccurrenceDetailIsKeptAtDebug() {
        // Demoted, not deleted — the per-occurrence trace is still there for debugging. The exact
        // count is an implementation detail of the fan-out, so assert only that it exceeds the
        // single operator-facing line.
        converter.convert(List.of(fanOutPolicy("brand_new_privilege")));

        assertTrue(count(Level.DEBUG, "brand_new_privilege") > 1,
                "the adapter should still trace every occurrence at DEBUG");
    }

    @Test
    void knownUnmappableAccessTypeIsSilentAtOperatorLevels() {
        // `refresh` and `index` are declared unmappable by HiveServiceAdapter: a known, safe drop,
        // already reported as an informational gap and rolled up in the assessment report. The
        // sync server must not log these at an operator-facing level at all.
        converter.convert(List.of(fanOutPolicy("refresh")));
        converter.convert(List.of(fanOutPolicy("index")));

        assertEquals(0, count(Level.ERROR, "refresh"), "a known safe drop must not log at ERROR");
        assertEquals(0, count(Level.WARN, "refresh"), "a known safe drop must not log at WARN");
        assertEquals(0, count(Level.ERROR, "index"), "a known safe drop must not log at ERROR");
        assertEquals(0, count(Level.WARN, "index"), "a known safe drop must not log at WARN");
    }

    @Test
    void aPolicyWithBothCategoriesLogsOnlyTheUnrecognizedOne() {
        RangerPolicy policy = fanOutPolicy("brand_new_privilege");
        // Add a known-unmappable type alongside the unrecognised one.
        RangerPolicyItemAccess refresh = new RangerPolicyItemAccess();
        refresh.setType("refresh");
        refresh.setIsAllowed(true);
        List<RangerPolicyItemAccess> accesses =
                new ArrayList<>(policy.getPolicyItems().get(0).getAccesses());
        accesses.add(refresh);
        policy.getPolicyItems().get(0).setAccesses(accesses);

        converter.convert(List.of(policy));

        assertEquals(1, count(Level.ERROR, "brand_new_privilege"),
                "the unrecognised type is still reported once");
        assertEquals(0, count(Level.ERROR, "refresh"),
                "the known safe drop in the same policy stays quiet");
    }
}
