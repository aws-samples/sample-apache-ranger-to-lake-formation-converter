package com.amazonaws.policyconverters.ranger;

import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GlobPatternDetector}.
 *
 * Validates: Requirements 2.1, 2.2
 */
class GlobPatternDetectorTest {

    // -----------------------------------------------------------------------
    // isGlobPattern tests (Requirement 2.1)
    // -----------------------------------------------------------------------

    @Test
    void isGlobPattern_starWildcard_returnsTrue() {
        assertTrue(GlobPatternDetector.isGlobPattern("table_*"));
    }

    @Test
    void isGlobPattern_questionMarkWildcard_returnsTrue() {
        assertTrue(GlobPatternDetector.isGlobPattern("db_?"));
    }

    @Test
    void isGlobPattern_bareAsterisk_returnsFalse() {
        assertFalse(GlobPatternDetector.isGlobPattern("*"));
    }

    @Test
    void isGlobPattern_exactName_returnsFalse() {
        assertFalse(GlobPatternDetector.isGlobPattern("exact_name"));
    }

    @Test
    void isGlobPattern_emptyString_returnsFalse() {
        assertFalse(GlobPatternDetector.isGlobPattern(""));
    }

    @Test
    void isGlobPattern_null_returnsFalse() {
        assertFalse(GlobPatternDetector.isGlobPattern(null));
    }

    // -----------------------------------------------------------------------
    // hasGlobPatterns tests (Requirement 2.1, 2.2)
    // -----------------------------------------------------------------------

    @Test
    void hasGlobPatterns_globInDatabase_returnsTrue() {
        RangerPolicy policy = policyWithResources("db_*", "exact_table", "exact_col");
        assertTrue(GlobPatternDetector.hasGlobPatterns(policy));
    }

    @Test
    void hasGlobPatterns_globInTable_returnsTrue() {
        RangerPolicy policy = policyWithResources("exact_db", "table_?", "exact_col");
        assertTrue(GlobPatternDetector.hasGlobPatterns(policy));
    }

    @Test
    void hasGlobPatterns_globInColumn_returnsTrue() {
        RangerPolicy policy = policyWithResources("exact_db", "exact_table", "col_*");
        assertTrue(GlobPatternDetector.hasGlobPatterns(policy));
    }

    @Test
    void hasGlobPatterns_noGlobs_returnsFalse() {
        RangerPolicy policy = policyWithResources("exact_db", "exact_table", "exact_col");
        assertFalse(GlobPatternDetector.hasGlobPatterns(policy));
    }

    @Test
    void hasGlobPatterns_bareAsteriskOnly_returnsFalse() {
        RangerPolicy policy = policyWithResources("*", "*", "*");
        assertFalse(GlobPatternDetector.hasGlobPatterns(policy));
    }

    @Test
    void hasGlobPatterns_nullPolicy_returnsFalse() {
        assertFalse(GlobPatternDetector.hasGlobPatterns(null));
    }

    @Test
    void hasGlobPatterns_nullResources_returnsFalse() {
        RangerPolicy policy = new RangerPolicy();
        policy.setResources(null);
        assertFalse(GlobPatternDetector.hasGlobPatterns(policy));
    }

    @Test
    void hasGlobPatterns_emptyResources_returnsFalse() {
        RangerPolicy policy = new RangerPolicy();
        policy.setResources(new HashMap<>());
        assertFalse(GlobPatternDetector.hasGlobPatterns(policy));
    }

    // -----------------------------------------------------------------------
    // filterGlobPolicies tests (Requirement 2.2)
    // -----------------------------------------------------------------------

    @Test
    void filterGlobPolicies_mixedList_returnsOnlyGlobPolicies() {
        RangerPolicy globPolicy1 = policyWithResources("db_*", "exact_table", "exact_col");
        RangerPolicy exactPolicy = policyWithResources("exact_db", "exact_table", "exact_col");
        RangerPolicy globPolicy2 = policyWithResources("exact_db", "table_?", "exact_col");
        RangerPolicy bareStarPolicy = policyWithResources("*", "*", "*");

        List<RangerPolicy> input = List.of(globPolicy1, exactPolicy, globPolicy2, bareStarPolicy);
        List<RangerPolicy> result = GlobPatternDetector.filterGlobPolicies(input);

        assertEquals(2, result.size());
        assertSame(globPolicy1, result.get(0));
        assertSame(globPolicy2, result.get(1));
    }

    @Test
    void filterGlobPolicies_nullList_returnsEmpty() {
        List<RangerPolicy> result = GlobPatternDetector.filterGlobPolicies(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void filterGlobPolicies_emptyList_returnsEmpty() {
        List<RangerPolicy> result = GlobPatternDetector.filterGlobPolicies(List.of());
        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private RangerPolicy policyWithResources(String dbValue, String tableValue, String colValue) {
        RangerPolicy policy = new RangerPolicy();
        Map<String, RangerPolicyResource> resources = new HashMap<>();

        RangerPolicyResource dbResource = new RangerPolicyResource();
        dbResource.setValues(List.of(dbValue));
        resources.put("database", dbResource);

        RangerPolicyResource tableResource = new RangerPolicyResource();
        tableResource.setValues(List.of(tableValue));
        resources.put("table", tableResource);

        RangerPolicyResource colResource = new RangerPolicyResource();
        colResource.setValues(List.of(colValue));
        resources.put("column", colResource);

        policy.setResources(resources);
        return policy;
    }
}
