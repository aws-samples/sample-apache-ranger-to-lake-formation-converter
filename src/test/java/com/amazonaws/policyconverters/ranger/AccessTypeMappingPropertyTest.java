package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import net.jqwik.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 2: Access Type Mapping Validity

/**
 * Property-based test verifying that each service adapter (Hive, Presto, Trino)
 * returns non-empty valid Cedar actions for known access types, and returns an
 * empty set for unknown access types.
 *
 * **Validates: Requirements 3.2, 3.5, 4.2, 4.5, 5.2, 5.5**
 */
@Tag("multi-ranger-plugin-support")
class AccessTypeMappingPropertyTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");

    private static final Set<String> VALID_CEDAR_ACTIONS = Set.of(
            "SELECT", "INSERT", "DELETE", "CREATE_TABLE", "DROP", "ALTER",
            "DESCRIBE", "SUPER", "ALL", "CREATE_DATABASE", "DATA_LOCATION_ACCESS"
    );

    private final HiveServiceAdapter hiveAdapter = new HiveServiceAdapter(AWS_CONTEXT);
    private final PrestoServiceAdapter prestoAdapter = new PrestoServiceAdapter(AWS_CONTEXT, "awsdatacatalog");
    private final TrinoServiceAdapter trinoAdapter = new TrinoServiceAdapter(AWS_CONTEXT, "awsdatacatalog");

    // ---- Hive: known mapped access types produce non-empty valid Cedar actions ----

    @Property(tries = 100)
    void hiveKnownMappedAccessTypesReturnNonEmptyValidActions(
            @ForAll("hiveMappedAccessTypes") String accessType
    ) {
        Set<String> actions = hiveAdapter.mapAccessTypeToCedarActions(accessType);

        assertFalse(actions.isEmpty(),
                "Hive mapped access type '" + accessType + "' should return non-empty Cedar actions");
        for (String action : actions) {
            assertTrue(VALID_CEDAR_ACTIONS.contains(action),
                    "Hive action '" + action + "' for access type '" + accessType + "' is not a valid Cedar action");
        }
    }

    // ---- Hive: unmapped and unknown access types produce empty set ----

    @Property(tries = 100)
    void hiveUnknownAccessTypesReturnEmptySet(
            @ForAll("unknownHiveAccessTypes") String accessType
    ) {
        Set<String> actions = hiveAdapter.mapAccessTypeToCedarActions(accessType);

        assertTrue(actions.isEmpty(),
                "Hive unknown access type '" + accessType + "' should return empty set but got: " + actions);
    }

    // ---- Presto: known mapped access types produce non-empty valid Cedar actions ----

    @Property(tries = 100)
    void prestoKnownMappedAccessTypesReturnNonEmptyValidActions(
            @ForAll("prestoMappedAccessTypes") String accessType
    ) {
        Set<String> actions = prestoAdapter.mapAccessTypeToCedarActions(accessType);

        assertFalse(actions.isEmpty(),
                "Presto mapped access type '" + accessType + "' should return non-empty Cedar actions");
        for (String action : actions) {
            assertTrue(VALID_CEDAR_ACTIONS.contains(action),
                    "Presto action '" + action + "' for access type '" + accessType + "' is not a valid Cedar action");
        }
    }

    // ---- Presto: unmapped and unknown access types produce empty set ----

    @Property(tries = 100)
    void prestoUnknownAccessTypesReturnEmptySet(
            @ForAll("unknownPrestoAccessTypes") String accessType
    ) {
        Set<String> actions = prestoAdapter.mapAccessTypeToCedarActions(accessType);

        assertTrue(actions.isEmpty(),
                "Presto unknown access type '" + accessType + "' should return empty set but got: " + actions);
    }

    // ---- Trino: known mapped access types produce non-empty valid Cedar actions ----

    @Property(tries = 100)
    void trinoKnownMappedAccessTypesReturnNonEmptyValidActions(
            @ForAll("trinoMappedAccessTypes") String accessType
    ) {
        Set<String> actions = trinoAdapter.mapAccessTypeToCedarActions(accessType);

        assertFalse(actions.isEmpty(),
                "Trino mapped access type '" + accessType + "' should return non-empty Cedar actions");
        for (String action : actions) {
            assertTrue(VALID_CEDAR_ACTIONS.contains(action),
                    "Trino action '" + action + "' for access type '" + accessType + "' is not a valid Cedar action");
        }
    }

    // ---- Trino: unmapped and unknown access types produce empty set ----

    @Property(tries = 100)
    void trinoUnknownAccessTypesReturnEmptySet(
            @ForAll("unknownTrinoAccessTypes") String accessType
    ) {
        Set<String> actions = trinoAdapter.mapAccessTypeToCedarActions(accessType);

        assertTrue(actions.isEmpty(),
                "Trino unknown access type '" + accessType + "' should return empty set but got: " + actions);
    }

    // --- Arbitraries ---

    /** Hive access types that have a non-empty Cedar mapping. */
    @Provide
    Arbitrary<String> hiveMappedAccessTypes() {
        return Arbitraries.of("select", "update", "create", "drop", "alter", "read", "write", "all");
    }

    /** Strings that are NOT in Hive's known mapping table (neither mapped nor unmapped). */
    @Provide
    Arbitrary<String> unknownHiveAccessTypes() {
        Set<String> allKnownHive = Set.of(
                "select", "update", "create", "drop", "alter", "read", "write", "all", "index", "lock"
        );
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !allKnownHive.contains(s.trim().toLowerCase()));
    }

    /** Presto access types that have a non-empty Cedar mapping. */
    @Provide
    Arbitrary<String> prestoMappedAccessTypes() {
        return Arbitraries.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");
    }

    /** Strings that are NOT in Presto's known mapping table. */
    @Provide
    Arbitrary<String> unknownPrestoAccessTypes() {
        Set<String> allKnownPresto = Set.of(
                "select", "insert", "delete", "create", "drop", "alter", "use", "show", "grant", "revoke"
        );
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !allKnownPresto.contains(s.trim().toLowerCase()));
    }

    /** Trino access types that have a non-empty Cedar mapping (same as Presto). */
    @Provide
    Arbitrary<String> trinoMappedAccessTypes() {
        return Arbitraries.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");
    }

    /** Strings that are NOT in Trino's known mapping table (same as Presto). */
    @Provide
    Arbitrary<String> unknownTrinoAccessTypes() {
        Set<String> allKnownTrino = Set.of(
                "select", "insert", "delete", "create", "drop", "alter", "use", "show", "grant", "revoke"
        );
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !allKnownTrino.contains(s.trim().toLowerCase()));
    }
}
