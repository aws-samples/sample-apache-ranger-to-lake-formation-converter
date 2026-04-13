package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 4: GDC Catalog Filtering

/**
 * Property-based test verifying that Presto and Trino adapters process a policy
 * if and only if the policy's catalog resource value matches the configured
 * {@code gdcCatalogName}. Policies targeting any other catalog are skipped.
 *
 * <p><b>Validates: Requirements 4.7, 4.8, 5.7, 5.8</b>
 */
@Tag("multi-ranger-plugin-support")
class GdcCatalogFilteringPropertyTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");
    private static final String GDC_CATALOG = "awsdatacatalog";

    private final PrestoServiceAdapter prestoAdapter = new PrestoServiceAdapter(AWS_CONTEXT, GDC_CATALOG);
    private final TrinoServiceAdapter trinoAdapter = new TrinoServiceAdapter(AWS_CONTEXT, GDC_CATALOG);

    // ---- Presto: matching catalog → shouldProcessPolicy returns true ----

    @Property(tries = 100)
    void prestoShouldProcessPolicyWhenCatalogMatchesGdcCatalogName() {
        RangerPolicy policy = buildPolicyWithCatalog(GDC_CATALOG);

        assertTrue(prestoAdapter.shouldProcessPolicy(policy),
                "Presto adapter should process policy when catalog matches gdcCatalogName");
    }

    // ---- Presto: non-matching catalog → shouldProcessPolicy returns false ----

    @Property(tries = 100)
    void prestoShouldSkipPolicyWhenCatalogDoesNotMatchGdcCatalogName(
            @ForAll("nonMatchingCatalogNames") String catalogValue
    ) {
        RangerPolicy policy = buildPolicyWithCatalog(catalogValue);

        assertFalse(prestoAdapter.shouldProcessPolicy(policy),
                "Presto adapter should skip policy when catalog '" + catalogValue
                        + "' does not match gdcCatalogName '" + GDC_CATALOG + "'");
    }

    // ---- Trino: matching catalog → shouldProcessPolicy returns true ----

    @Property(tries = 100)
    void trinoShouldProcessPolicyWhenCatalogMatchesGdcCatalogName() {
        RangerPolicy policy = buildPolicyWithCatalog(GDC_CATALOG);

        assertTrue(trinoAdapter.shouldProcessPolicy(policy),
                "Trino adapter should process policy when catalog matches gdcCatalogName");
    }

    // ---- Trino: non-matching catalog → shouldProcessPolicy returns false ----

    @Property(tries = 100)
    void trinoShouldSkipPolicyWhenCatalogDoesNotMatchGdcCatalogName(
            @ForAll("nonMatchingCatalogNames") String catalogValue
    ) {
        RangerPolicy policy = buildPolicyWithCatalog(catalogValue);

        assertFalse(trinoAdapter.shouldProcessPolicy(policy),
                "Trino adapter should skip policy when catalog '" + catalogValue
                        + "' does not match gdcCatalogName '" + GDC_CATALOG + "'");
    }

    // ---- Both adapters: random gdcCatalogName, matching catalog always accepted ----

    @Property(tries = 100)
    void bothAdaptersAcceptMatchingCatalogForRandomGdcName(
            @ForAll("catalogNames") String gdcName
    ) {
        PrestoServiceAdapter presto = new PrestoServiceAdapter(AWS_CONTEXT, gdcName);
        TrinoServiceAdapter trino = new TrinoServiceAdapter(AWS_CONTEXT, gdcName);
        RangerPolicy policy = buildPolicyWithCatalog(gdcName);

        assertTrue(presto.shouldProcessPolicy(policy),
                "Presto should accept policy when catalog matches random gdcCatalogName '" + gdcName + "'");
        assertTrue(trino.shouldProcessPolicy(policy),
                "Trino should accept policy when catalog matches random gdcCatalogName '" + gdcName + "'");
    }

    // ---- Both adapters: random gdcCatalogName, different catalog always rejected ----

    @Property(tries = 100)
    void bothAdaptersRejectNonMatchingCatalogForRandomGdcName(
            @ForAll("catalogNames") String gdcName,
            @ForAll("catalogNames") String catalogValue
    ) {
        Assume.that(!gdcName.equals(catalogValue));

        PrestoServiceAdapter presto = new PrestoServiceAdapter(AWS_CONTEXT, gdcName);
        TrinoServiceAdapter trino = new TrinoServiceAdapter(AWS_CONTEXT, gdcName);
        RangerPolicy policy = buildPolicyWithCatalog(catalogValue);

        assertFalse(presto.shouldProcessPolicy(policy),
                "Presto should reject policy when catalog '" + catalogValue
                        + "' differs from gdcCatalogName '" + gdcName + "'");
        assertFalse(trino.shouldProcessPolicy(policy),
                "Trino should reject policy when catalog '" + catalogValue
                        + "' differs from gdcCatalogName '" + gdcName + "'");
    }

    // --- Helper ---

    private static RangerPolicy buildPolicyWithCatalog(String catalogValue) {
        RangerPolicy policy = new RangerPolicy();
        RangerPolicyResource catalogResource = new RangerPolicyResource();
        catalogResource.setValues(List.of(catalogValue));
        policy.setResources(Map.of("catalog", catalogResource));
        return policy;
    }

    // --- Arbitraries ---

    /** Generates catalog names that do NOT match the fixed GDC_CATALOG constant. */
    @Provide
    Arbitrary<String> nonMatchingCatalogNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('_', '-')
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(s -> !s.equals(GDC_CATALOG));
    }

    /** Generates random catalog name strings. */
    @Provide
    Arbitrary<String> catalogNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('_', '-')
                .ofMinLength(1)
                .ofMaxLength(30);
    }
}
