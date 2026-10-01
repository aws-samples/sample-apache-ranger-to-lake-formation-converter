package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the one assumption that makes {@code NARROWED_ACTION} reporting correct.
 *
 * <p><b>Background.</b> Exactly one narrowing exists in the converter today: a column-scoped
 * {@code all} becomes {@code SELECT}, because Lake Formation rejects {@code ALL} on a
 * {@code TableWithColumns} resource. Because there is only one, the target permission and the
 * explanation are stored as two shared constants —
 * {@link RangerToCedarConverter#NARROWED_ACTION_TARGET} and
 * {@link RangerToCedarConverter#NARROWED_ACTION_REASON} — and stamped onto every row of the
 * assessment's narrowed-action rollup.
 *
 * <p><b>Why that is fragile.</b> The converter accumulates narrowings as a bare
 * {@code Set<String>} of access-type names. By the time the rollup is built, the name is all that
 * survives; nothing records what each access type was narrowed <em>to</em>. So the moment a second
 * narrowing with a different target exists, every rollup row still claims
 * {@code narrowedTo: "SELECT"} and quotes the SELECT-specific reason. The output stays well-formed
 * and machine-readable while being false, and no existing test notices.
 *
 * <p><b>What this test does.</b> It sweeps every adapter, access type and catalog resource level,
 * and asserts that any narrowing produces exactly {@code NARROWED_ACTION_TARGET}. If someone adds a
 * narrowing to a different permission, this fails immediately and points at the work required.
 *
 * <p><b>IF THIS TEST FAILS, DO NOT SIMPLY WIDEN THE ASSERTION.</b> A new narrowing target means the
 * shared-constant model no longer holds, and the following must be implemented first:
 *
 * <ol>
 *   <li>Replace the {@code Set<String> narrowedAccessTypes} accumulator in
 *       {@code RangerToCedarConverter.convertSinglePolicy} with a set of records carrying
 *       {@code (accessType, narrowedTo, reason)}.</li>
 *   <li>Thread that richer type through all four {@code generateStatements} call sites and into
 *       {@code extractCedarActions}, where the narrowing is detected.</li>
 *   <li>Carry {@code narrowedTo} and {@code reason} on the {@code GapEntry} (or an equivalent
 *       side-channel) so the assessment layer no longer has to guess them.</li>
 *   <li>Group {@code AssessmentRunner.buildNarrowedActionRollup} by
 *       {@code (serviceType, accessType, narrowedTo)} instead of {@code (serviceType, accessType)},
 *       and populate {@link com.amazonaws.policyconverters.assessment.NarrowedActionSummary} from
 *       the per-entry values rather than the shared constants.</li>
 *   <li>Delete {@code NARROWED_ACTION_TARGET} / {@code NARROWED_ACTION_REASON} and this test.</li>
 * </ol>
 *
 * <p>Deliberately excluded: the {@code url} and {@code datalocation} levels. Those <em>translate</em>
 * an access type to {@code DATA_LOCATION_ACCESS} rather than narrowing it — Lake Formation accepts
 * only that permission on a data location, so the result differs from the raw mapping for a
 * different reason and is not reported as a narrowing.
 */
class NarrowingInvariantTest {

    private static final AwsContext CTX =
            new AwsContext("us-east-1", "123456789012", "123456789012");

    /** Catalog resource levels only — see the class comment on why url/datalocation are excluded. */
    private static final List<String> CATALOG_LEVELS =
            List.of("column", "table", "database", "schema");

    /**
     * Union of access types across every bundled Ranger service definition. A new access type added
     * to any adapter should be added here too, so the sweep keeps its coverage.
     */
    private static final List<String> ACCESS_TYPES = List.of(
            "select", "insert", "update", "delete", "describe", "alter", "drop",
            "create", "create_table", "create_database", "read", "write", "all",
            "use", "show", "index", "lock", "refresh", "repladmin", "serviceadmin",
            "tempudfadmin", "grant", "revoke", "execute", "impersonate",
            "datalocation", "data_location_access");

    private static Map<String, SourcePolicyAdapter> adapters() {
        Map<String, SourcePolicyAdapter> m = new LinkedHashMap<>();
        m.put("lakeformation", new RangerServiceAdapter(CTX));
        m.put("hive", new HiveServiceAdapter(CTX));
        m.put("amazon-emr-spark", new EmrSparkServiceAdapter(CTX));
        m.put("trino", new TrinoServiceAdapter(CTX, "hive"));
        m.put("presto", new PrestoServiceAdapter(CTX, "hive"));
        return m;
    }

    @Test
    void everyNarrowingProducesTheSingleSharedTarget() {
        Set<String> expected = Set.of(RangerToCedarConverter.NARROWED_ACTION_TARGET);

        for (Map.Entry<String, SourcePolicyAdapter> e : adapters().entrySet()) {
            SourcePolicyAdapter adapter = e.getValue();
            for (String accessType : ACCESS_TYPES) {
                Set<String> raw = adapter.mapAccessTypeToCedarActions(accessType);
                if (raw.isEmpty()) {
                    continue; // unmapped or deliberately unmappable — not a narrowing
                }
                for (String level : CATALOG_LEVELS) {
                    Set<String> atLevel = adapter.mapAccessTypeToCedarActions(accessType, level);

                    // Empty = filtered out entirely at this level (e.g. SELECT at database level).
                    // Equal to raw = passed through untouched. Neither is a narrowing.
                    if (atLevel.isEmpty() || atLevel.equals(raw)) {
                        continue;
                    }

                    assertEquals(expected, atLevel, String.format(
                            "New narrowing found: service '%s' narrows access type '%s' from %s to "
                                    + "%s at resource level '%s'. The narrowed-action rollup stores "
                                    + "the target and reason as shared constants, so this row would "
                                    + "be reported as narrowedTo='%s' — silently wrong. See this "
                                    + "test's class comment for the five steps required before this "
                                    + "assertion may be changed.",
                            e.getKey(), accessType, raw, atLevel, level,
                            RangerToCedarConverter.NARROWED_ACTION_TARGET));
                }
            }
        }
    }

    @Test
    void theKnownNarrowingIsStillDetectedBySweep() {
        // Sanity check on the sweep itself: if the column-scoped `all` narrowing ever stops being
        // visible to this test, the test above would pass vacuously and guard nothing.
        HiveServiceAdapter hive = new HiveServiceAdapter(CTX);

        Set<String> raw = hive.mapAccessTypeToCedarActions("all");
        Set<String> atColumn = hive.mapAccessTypeToCedarActions("all", "column");

        assertEquals(Set.of("ALL"), raw, "precondition: 'all' maps to ALL before level filtering");
        assertEquals(Set.of(RangerToCedarConverter.NARROWED_ACTION_TARGET), atColumn,
                "the one known narrowing must remain observable through the adapter interface");
    }
}
