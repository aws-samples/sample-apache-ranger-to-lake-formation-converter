package com.amazonaws.policyconverters.lakeformation;
import com.amazonaws.policyconverters.model.ReverseSyncDryRunOutput;

import com.amazonaws.policyconverters.model.DriftReport;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for {@link ReverseSyncDryRunOutput} JSON round-trip serialization.
 * Validates: Requirements 6.1, 6.2, 6.3
 */
class ReverseSyncDryRunOutputRoundTripPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 14: Dry-run reverse-sync round-trip
    // **Validates: Requirements 6.1, 6.2, 6.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void dryRunReverseSyncRoundTrip(
            @ForAll("reverseSyncDryRunOutputs") ReverseSyncDryRunOutput original) throws Exception {

        String json = MAPPER.writeValueAsString(original);
        ReverseSyncDryRunOutput deserialized = MAPPER.readValue(json, ReverseSyncDryRunOutput.class);

        assertEquals(original, deserialized,
                "Serializing ReverseSyncDryRunOutput to JSON and deserializing back should produce an equivalent object");
        assertEquals(original.getTimestamp(), deserialized.getTimestamp());
        assertEquals(original.getSequenceNumber(), deserialized.getSequenceNumber());
        assertEquals(original.getOperations(), deserialized.getOperations());
        assertEquals(original.getDriftSummary(), deserialized.getDriftSummary());
    }

    // -----------------------------------------------------------------------
    // Arbitraries (generators)
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<ReverseSyncDryRunOutput> reverseSyncDryRunOutputs() {
        return Combinators.combine(timestamps(), sequenceNumbers(), operationLists(), driftReports())
                .as(ReverseSyncDryRunOutput::new);
    }

    private Arbitrary<String> timestamps() {
        return Arbitraries.of(
                "2024-01-15T10:30:00Z",
                "2024-06-01T00:00:00Z",
                "2025-03-20T14:45:30Z",
                "2023-12-31T23:59:59Z"
        );
    }

    private Arbitrary<Integer> sequenceNumbers() {
        return Arbitraries.integers().between(1, 999);
    }

    private Arbitrary<List<LFPermissionOperation>> operationLists() {
        return lfPermissionOperations().list().ofMinSize(0).ofMaxSize(5);
    }

    private Arbitrary<LFPermissionOperation> lfPermissionOperations() {
        Arbitrary<LFPermissionOperation.OperationType> opTypes =
                Arbitraries.of(LFPermissionOperation.OperationType.values());
        Arbitrary<String> policyIds = Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.of("policy-1", "policy-2", "policy-abc")
        );
        Arbitrary<String> principals = Arbitraries.of(
                "arn:aws:iam::123456789012:role/TestRole",
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::987654321098:role/DataAnalyst"
        );
        Arbitrary<Boolean> grantables = Arbitraries.of(true, false);

        return Combinators.combine(opTypes, policyIds, principals, lfResources(), permissionSets(), grantables)
                .as(LFPermissionOperation::new);
    }

    private Arbitrary<LFResource> lfResources() {
        return Arbitraries.oneOf(
                databaseResources(),
                tableResources(),
                columnResources(),
                dataLocationResources()
        );
    }

    private Arbitrary<LFResource> databaseResources() {
        return Combinators.combine(catalogIds(), databaseNames())
                .as((cat, db) -> new LFResource(cat, db, null, null, null));
    }

    private Arbitrary<LFResource> tableResources() {
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "orders", "*");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames)
                .as((cat, db, tbl) -> new LFResource(cat, db, tbl, null, null));
    }

    private Arbitrary<LFResource> columnResources() {
        Arbitrary<Set<String>> colSets = Arbitraries.of("col1", "col2", "id", "name")
                .set().ofMinSize(1).ofMaxSize(3);
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "orders");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames, colSets)
                .as((cat, db, tbl, cols) -> new LFResource(cat, db, tbl, cols, null));
    }

    private Arbitrary<LFResource> dataLocationResources() {
        return Arbitraries.of(
                "arn:aws:s3:::bucket-a/path1",
                "arn:aws:s3:::bucket-b/data",
                "arn:aws:s3:::lake/raw"
        ).map(path -> new LFResource(null, null, null, null, null, path));
    }

    private Arbitrary<String> catalogIds() {
        return Arbitraries.of("123456789012", "987654321098");
    }

    private Arbitrary<String> databaseNames() {
        return Arbitraries.of("analytics", "finance", "hr");
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set().ofMinSize(1).ofMaxSize(4);
    }

    private Arbitrary<DriftReport> driftReports() {
        Arbitrary<Integer> counts = Arbitraries.integers().between(0, 50);
        return Combinators.combine(counts, counts, counts)
                .as((missing, extra, inSync) -> new DriftReport(
                        missing, extra, inSync,
                        Collections.emptyList(),
                        Collections.emptyList()));
    }
}
