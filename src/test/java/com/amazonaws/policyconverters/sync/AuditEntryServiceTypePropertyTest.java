package com.amazonaws.policyconverters.sync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import net.jqwik.api.*;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 12: Audit Entry Service Type Inclusion

/**
 * Property-based tests for audit log service type inclusion.
 *
 * Property 12: For any grant or revoke operation derived from a Cedar policy with a
 * service-type-prefixed @source annotation, the audit log entry SHALL include the
 * originating service type.
 *
 * **Validates: Requirements 12.1, 12.2**
 */
@Tag("multi-ranger-plugin-support")
class AuditEntryServiceTypePropertyTest {

    private static final String CATALOG_ID = "123456789012";

    // -----------------------------------------------------------------------
    // Property 12: Audit Entry Service Type Inclusion
    // **Validates: Requirements 12.1, 12.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void auditEntryContainsServiceTypeFromPrefixedSourceId(
            @ForAll("serviceTypePrefixedOperations") LFPermissionOperation op
    ) {
        Logger auditLogger = (Logger) LoggerFactory.getLogger(
                SyncService.class.getName() + ".audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);

        try {
            SyncService.logAuditEntry(op);

            assertEquals(1, appender.list.size(),
                    "Exactly one audit log entry should be emitted");

            String logMessage = appender.list.get(0).getFormattedMessage();

            // Extract expected service type from the source policy ID prefix
            String expectedServiceType = SyncService.parseServiceType(op.getSourcePolicyId());

            // The audit entry must contain serviceType= field with the correct value
            assertTrue(logMessage.contains("serviceType=" + expectedServiceType),
                    "Audit entry must contain serviceType=" + expectedServiceType
                            + ", got: " + logMessage);

            // The audit entry must still contain all other required fields
            assertTrue(logMessage.contains(op.getSourcePolicyId()),
                    "Audit entry must contain source policy ID");
            assertTrue(logMessage.contains(op.getOperationType().getValue()),
                    "Audit entry must contain operation type");
            assertTrue(logMessage.contains(op.getPrincipalArn()),
                    "Audit entry must contain principal ARN");
        } finally {
            auditLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Property(tries = 100)
    void parseServiceTypeExtractsPrefix(
            @ForAll("serviceTypes") String serviceType,
            @ForAll("policyIds") String policyId
    ) {
        String sourcePolicyId = serviceType + ":" + policyId;
        String parsed = SyncService.parseServiceType(sourcePolicyId);
        assertEquals(serviceType, parsed,
                "parseServiceType should extract '" + serviceType + "' from '" + sourcePolicyId + "'");
    }

    @Property(tries = 100)
    void parseServiceTypeReturnsUnknownForUnprefixedIds(
            @ForAll("policyIds") String policyId
    ) {
        // Plain numeric IDs without a colon should return "unknown"
        String parsed = SyncService.parseServiceType(policyId);
        assertEquals("unknown", parsed,
                "parseServiceType should return 'unknown' for unprefixed ID: " + policyId);
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<LFPermissionOperation> serviceTypePrefixedOperations() {
        Arbitrary<String> serviceTypes = Arbitraries.of(
                "hive", "lakeformation", "presto", "trino");
        Arbitrary<String> policyIds = Arbitraries.integers().between(1, 1000)
                .map(String::valueOf);
        Arbitrary<String> sourcePolicyIds = Combinators.combine(serviceTypes, policyIds)
                .as((st, pid) -> st + ":" + pid);
        Arbitrary<OperationType> opTypes = Arbitraries.of(
                OperationType.GRANT, OperationType.REVOKE);
        Arbitrary<String> principalArns = Arbitraries.of(
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::123456789012:user/bob",
                "arn:aws:iam::123456789012:role/DataAnalyst");
        Arbitrary<LFResource> resources = lfResources();
        Arbitrary<Set<LFPermission>> permissions = permissionSets();
        Arbitrary<Boolean> grantable = Arbitraries.of(true, false);

        return Combinators.combine(opTypes, sourcePolicyIds, principalArns,
                        resources, permissions, grantable)
                .as(LFPermissionOperation::new);
    }

    @Provide
    Arbitrary<String> serviceTypes() {
        return Arbitraries.of("hive", "lakeformation", "presto", "trino");
    }

    @Provide
    Arbitrary<String> policyIds() {
        return Arbitraries.integers().between(1, 1000).map(String::valueOf);
    }

    private Arbitrary<LFResource> lfResources() {
        Arbitrary<String> databases = Arbitraries.of("analytics", "finance", "hr");
        Arbitrary<String> tables = Arbitraries.of("events", "users", "transactions");

        return Combinators.combine(databases, tables, Arbitraries.of(true, false))
                .as((db, table, includeTable) -> new LFResource(
                        CATALOG_ID, db,
                        includeTable ? table : null,
                        null, null));
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(3);
    }
}
