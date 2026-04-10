package com.amazonaws.policyconverters.ranger;

import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for GlobPatternDetector.
 *
 * Feature: wildcard-pattern-refresh, Property 2: Glob pattern detection is correct for all resource strings
 * **Validates: Requirements 2.1**
 */
@Tag("Feature: wildcard-pattern-refresh, Property 2: Glob pattern detection is correct for all resource strings")
class GlobPatternDetectorPropertyTest {

    // -----------------------------------------------------------------------
    // Property 2: Glob pattern detection is correct for all resource strings
    // **Validates: Requirements 2.1**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void isGlobPatternReturnsTrueForStringsContainingWildcardButNotBareAsterisk(
            @ForAll("stringsWithGlobChars") String value
    ) {
        // Strings that contain * or ? but are not exactly "*" should be detected as glob patterns
        assertTrue(GlobPatternDetector.isGlobPattern(value),
                "Expected isGlobPattern to return true for: \"" + value + "\"");
    }

    @Property(tries = 100)
    void isGlobPatternReturnsFalseForStringsWithoutWildcardChars(
            @ForAll("stringsWithoutGlobChars") String value
    ) {
        // Strings that do not contain * or ? should not be detected as glob patterns
        assertFalse(GlobPatternDetector.isGlobPattern(value),
                "Expected isGlobPattern to return false for: \"" + value + "\"");
    }

    @Property(tries = 100)
    void bareAsteriskIsNotAGlobPattern() {
        // The bare "*" is handled by Lake Formation's TableWildcard and must be excluded
        assertFalse(GlobPatternDetector.isGlobPattern("*"),
                "Bare '*' should not be considered a glob pattern");
    }

    @Property(tries = 100)
    void isGlobPatternReturnsFalseForNullAndEmpty() {
        assertFalse(GlobPatternDetector.isGlobPattern(null),
                "null should not be considered a glob pattern");
        assertFalse(GlobPatternDetector.isGlobPattern(""),
                "Empty string should not be considered a glob pattern");
    }

    @Property(tries = 100)
    void isGlobPatternIsConsistentWithManualCheck(
            @ForAll("arbitraryStrings") String value
    ) {
        // For any string, isGlobPattern should return true iff:
        // 1. The string is not null and not empty
        // 2. The string is not exactly "*"
        // 3. The string contains '*' or '?'
        boolean expected = value != null
                && !value.isEmpty()
                && !"*".equals(value)
                && (value.contains("*") || value.contains("?"));

        assertEquals(expected, GlobPatternDetector.isGlobPattern(value),
                "isGlobPattern mismatch for: \"" + value + "\"");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> stringsWithGlobChars() {
        // Generate strings that contain * or ? but are NOT exactly "*"
        Arbitrary<String> prefix = Arbitraries.strings()
                .ofMinLength(0).ofMaxLength(20)
                .withCharRange('a', 'z')
                .withChars('_', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
        Arbitrary<String> suffix = Arbitraries.strings()
                .ofMinLength(0).ofMaxLength(20)
                .withCharRange('a', 'z')
                .withChars('_', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
        Arbitrary<Character> globChar = Arbitraries.of('*', '?');

        return Combinators.combine(prefix, globChar, suffix)
                .as((p, g, s) -> p + g + s)
                .filter(s -> !"*".equals(s)); // exclude bare "*"
    }

    @Provide
    Arbitrary<String> stringsWithoutGlobChars() {
        // Generate strings that do NOT contain * or ? and are not empty
        return Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(50)
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-', '.', '/', ':', '@', '#', ' ')
                .filter(s -> !s.contains("*") && !s.contains("?"));
    }

    // -----------------------------------------------------------------------
    // Property 3: Wildcard policy filter returns exactly glob-containing policies
    // **Validates: Requirements 2.2**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: wildcard-pattern-refresh, Property 3: Wildcard policy filter returns exactly glob-containing policies")
    void filterGlobPoliciesReturnsExactlyPoliciesWhereHasGlobPatternsIsTrue(
            @ForAll("rangerPolicyLists") List<RangerPolicy> policies
    ) {
        List<RangerPolicy> filtered = GlobPatternDetector.filterGlobPolicies(policies);

        // Every returned policy must have glob patterns
        for (RangerPolicy p : filtered) {
            assertTrue(GlobPatternDetector.hasGlobPatterns(p),
                    "filterGlobPolicies returned a policy without glob patterns");
        }

        // Every policy with glob patterns must be in the result
        List<RangerPolicy> expectedGlob = policies.stream()
                .filter(GlobPatternDetector::hasGlobPatterns)
                .collect(Collectors.toList());

        assertEquals(expectedGlob.size(), filtered.size(),
                "filterGlobPolicies size mismatch: expected " + expectedGlob.size() + " but got " + filtered.size());

        // Same elements in same order
        for (int i = 0; i < expectedGlob.size(); i++) {
            assertSame(expectedGlob.get(i), filtered.get(i),
                    "filterGlobPolicies element mismatch at index " + i);
        }
    }

    @Property(tries = 100)
    @Tag("Feature: wildcard-pattern-refresh, Property 3: Wildcard policy filter returns exactly glob-containing policies")
    void filterGlobPoliciesPreservesOrderOfGlobPolicies(
            @ForAll("rangerPolicyLists") List<RangerPolicy> policies
    ) {
        List<RangerPolicy> filtered = GlobPatternDetector.filterGlobPolicies(policies);

        // Verify the filtered list preserves the relative order from the original list
        int lastIndex = -1;
        for (RangerPolicy fp : filtered) {
            int idx = policies.indexOf(fp);
            assertTrue(idx > lastIndex,
                    "filterGlobPolicies did not preserve order from original list");
            lastIndex = idx;
        }
    }

    @Property(tries = 100)
    @Tag("Feature: wildcard-pattern-refresh, Property 3: Wildcard policy filter returns exactly glob-containing policies")
    void filterGlobPoliciesNonGlobPoliciesAreExcluded(
            @ForAll("rangerPolicyLists") List<RangerPolicy> policies
    ) {
        List<RangerPolicy> filtered = GlobPatternDetector.filterGlobPolicies(policies);

        // No non-glob policy should appear in the result
        Set<RangerPolicy> filteredSet = new HashSet<>(filtered);
        for (RangerPolicy p : policies) {
            if (!GlobPatternDetector.hasGlobPatterns(p)) {
                assertFalse(filteredSet.contains(p),
                        "filterGlobPolicies included a non-glob policy");
            }
        }
    }

    @Property(tries = 100)
    @Tag("Feature: wildcard-pattern-refresh, Property 3: Wildcard policy filter returns exactly glob-containing policies")
    void filterGlobPoliciesOnNullOrEmptyReturnsEmpty() {
        assertTrue(GlobPatternDetector.filterGlobPolicies(null).isEmpty(),
                "filterGlobPolicies(null) should return empty list");
        assertTrue(GlobPatternDetector.filterGlobPolicies(List.of()).isEmpty(),
                "filterGlobPolicies(empty) should return empty list");
    }

    // --- Arbitraries for Property 3 ---

    @Provide
    Arbitrary<List<RangerPolicy>> rangerPolicyLists() {
        return rangerPolicies().list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<RangerPolicy> rangerPolicies() {
        return Combinators.combine(
                resourceValueArbitrary(), // database value
                resourceValueArbitrary(), // table value
                resourceValueArbitrary()  // column value
        ).as((dbVal, tableVal, colVal) -> {
            RangerPolicy policy = new RangerPolicy();
            Map<String, RangerPolicyResource> resources = new HashMap<>();

            if (dbVal != null) {
                RangerPolicyResource dbResource = new RangerPolicyResource();
                dbResource.setValues(List.of(dbVal));
                resources.put("database", dbResource);
            }
            if (tableVal != null) {
                RangerPolicyResource tableResource = new RangerPolicyResource();
                tableResource.setValues(List.of(tableVal));
                resources.put("table", tableResource);
            }
            if (colVal != null) {
                RangerPolicyResource colResource = new RangerPolicyResource();
                colResource.setValues(List.of(colVal));
                resources.put("column", colResource);
            }

            policy.setResources(resources);
            return policy;
        });
    }

    /**
     * Generates resource values: plain names, glob patterns, bare "*", or null (absent resource).
     */
    Arbitrary<String> resourceValueArbitrary() {
        Arbitrary<String> plainNames = Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(20)
                .withCharRange('a', 'z')
                .withChars('_', '-')
                .filter(s -> !s.contains("*") && !s.contains("?"));

        Arbitrary<String> globPatterns = Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(10)
                        .withCharRange('a', 'z').withChars('_'),
                Arbitraries.of('*', '?'),
                Arbitraries.strings().ofMinLength(0).ofMaxLength(10)
                        .withCharRange('a', 'z').withChars('_')
        ).as((prefix, glob, suffix) -> prefix + glob + suffix);

        Arbitrary<String> bareAsterisk = Arbitraries.just("*");

        return Arbitraries.oneOf(
                plainNames,       // no glob
                globPatterns,     // has glob
                bareAsterisk,     // bare * (excluded from glob detection)
                Arbitraries.just(null) // absent resource
        );
    }

    @Provide
    Arbitrary<String> arbitraryStrings() {
        // Mix of all kinds of strings: with globs, without globs, bare *, empty, unicode
        Arbitrary<String> unicodeBase = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('\u00e9', '\u00f1', '\u4e16', '\u754c')
                .ofMinLength(1).ofMaxLength(20);

        Arbitrary<String> unicodeWithGlob = Combinators.combine(unicodeBase, Arbitraries.of('*', '?'), unicodeBase)
                .as((p, g, s) -> p + g + s)
                .filter(s -> !"*".equals(s));

        return Arbitraries.oneOf(
                // Strings with glob characters (not bare *)
                stringsWithGlobChars(),
                // Strings without glob characters
                stringsWithoutGlobChars(),
                // Bare asterisk
                Arbitraries.just("*"),
                // Empty string
                Arbitraries.just(""),
                // Unicode strings without glob chars
                unicodeBase,
                // Unicode strings with glob chars
                unicodeWithGlob
        );
    }
}
