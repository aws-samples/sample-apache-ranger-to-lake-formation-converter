package com.amazonaws.policyconverters.cedar;

import com.cedarpolicy.model.exception.InternalException;
import net.jqwik.api.*;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for CedarPolicySet format/parse round-trip.
 *
 * Feature: cedar-policy-abstraction, Property 15: Cedar PolicySet Format/Parse Round-Trip
 * **Validates: Requirements 10.2, 10.3**
 *
 * toCedarString() → fromCedarString() produces semantically equivalent PolicySet:
 * same permit/forbid counts, same principals, same source policy IDs.
 */
class CedarPolicySetRoundTripPropertyTest {

    private static final String[] VALID_REGIONS = {
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-central-1", "ap-southeast-1", "ap-northeast-1"
    };

    // Action → valid resource entity types (per the Cedar schema)
    // Table-level actions
    private static final String[] TABLE_ACTIONS = {"SELECT", "INSERT", "UPDATE", "DELETE", "DESCRIBE", "ALTER", "DROP"};
    // Database-level actions
    private static final String[] DATABASE_ACTIONS = {"DESCRIBE", "ALTER", "DROP", "CREATE_TABLE"};

    // -----------------------------------------------------------------------
    // Property 15: Cedar PolicySet Format/Parse Round-Trip
    // For any valid CedarPolicySet, toCedarString() → fromCedarString()
    // produces a semantically equivalent CedarPolicySet.
    // **Validates: Requirements 10.2, 10.3**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void singlePermitRoundTrip(
            @ForAll("policyIds") String policyId,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String dbName,
            @ForAll("resourceNames") String tableName,
            @ForAll("tableActions") String action
    ) throws InternalException {
        String principalArn = "arn:aws:iam::" + accountId + ":role/TestRole";
        String resourceArn = "arn:aws:glue:us-east-1:" + accountId + ":table/" + dbName + "/" + tableName;

        String cedarText = "@source(\"" + policyId + "\")\n"
                + "permit(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn + "\",\n"
                + "    action == DataCatalog::Action::\"" + action + "\",\n"
                + "    resource == DataCatalog::Table::\"" + resourceArn + "\"\n"
                + ");";

        CedarPolicySet original = CedarPolicySet.fromCedarString(cedarText);
        String serialized = original.toCedarString();
        CedarPolicySet roundTripped = CedarPolicySet.fromCedarString(serialized);

        assertEquals(original.getPermitCount(), roundTripped.getPermitCount(),
                "Permit count should be preserved after round-trip");
        assertEquals(original.getForbidCount(), roundTripped.getForbidCount(),
                "Forbid count should be preserved after round-trip");
        assertEquals(original.getPrincipals(), roundTripped.getPrincipals(),
                "Principals should be preserved after round-trip");
        assertEquals(new HashSet<>(original.getSourcePolicyIds()), new HashSet<>(roundTripped.getSourcePolicyIds()),
                "Source policy IDs should be preserved after round-trip");
    }

    @Property(tries = 100)
    void singleForbidRoundTrip(
            @ForAll("policyIds") String policyId,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String dbName,
            @ForAll("resourceNames") String tableName,
            @ForAll("tableActions") String action
    ) throws InternalException {
        String principalArn = "arn:aws:iam::" + accountId + ":role/TestRole";
        String resourceArn = "arn:aws:glue:us-east-1:" + accountId + ":table/" + dbName + "/" + tableName;

        String cedarText = "@source(\"" + policyId + "\")\n"
                + "forbid(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn + "\",\n"
                + "    action == DataCatalog::Action::\"" + action + "\",\n"
                + "    resource == DataCatalog::Table::\"" + resourceArn + "\"\n"
                + ");";

        CedarPolicySet original = CedarPolicySet.fromCedarString(cedarText);
        String serialized = original.toCedarString();
        CedarPolicySet roundTripped = CedarPolicySet.fromCedarString(serialized);

        assertEquals(original.getPermitCount(), roundTripped.getPermitCount(),
                "Permit count should be preserved after round-trip");
        assertEquals(original.getForbidCount(), roundTripped.getForbidCount(),
                "Forbid count should be preserved after round-trip");
        assertEquals(original.getPrincipals(), roundTripped.getPrincipals(),
                "Principals should be preserved after round-trip");
        assertEquals(new HashSet<>(original.getSourcePolicyIds()), new HashSet<>(roundTripped.getSourcePolicyIds()),
                "Source policy IDs should be preserved after round-trip");
    }

    @Property(tries = 100)
    void mixedPermitForbidRoundTrip(
            @ForAll("policyIds") String permitPolicyId,
            @ForAll("policyIds") String forbidPolicyId,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String dbName,
            @ForAll("resourceNames") String tableName,
            @ForAll("tableActions") String action
    ) throws InternalException {
        String principalArn = "arn:aws:iam::" + accountId + ":role/TestRole";
        String resourceArn = "arn:aws:glue:us-east-1:" + accountId + ":table/" + dbName + "/" + tableName;

        String cedarText = "@source(\"" + permitPolicyId + "\")\n"
                + "permit(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn + "\",\n"
                + "    action == DataCatalog::Action::\"" + action + "\",\n"
                + "    resource == DataCatalog::Table::\"" + resourceArn + "\"\n"
                + ");\n"
                + "@source(\"" + forbidPolicyId + "\")\n"
                + "forbid(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn + "\",\n"
                + "    action == DataCatalog::Action::\"" + action + "\",\n"
                + "    resource == DataCatalog::Table::\"" + resourceArn + "\"\n"
                + ");";

        CedarPolicySet original = CedarPolicySet.fromCedarString(cedarText);
        String serialized = original.toCedarString();
        CedarPolicySet roundTripped = CedarPolicySet.fromCedarString(serialized);

        assertEquals(original.getPermitCount(), roundTripped.getPermitCount(),
                "Permit count should be preserved after round-trip");
        assertEquals(original.getForbidCount(), roundTripped.getForbidCount(),
                "Forbid count should be preserved after round-trip");
        assertEquals(original.getPrincipals(), roundTripped.getPrincipals(),
                "Principals should be preserved after round-trip");
        assertEquals(new HashSet<>(original.getSourcePolicyIds()), new HashSet<>(roundTripped.getSourcePolicyIds()),
                "Source policy IDs should be preserved after round-trip");
    }

    @Property(tries = 100)
    void multiPrincipalPolicySetRoundTrip(
            @ForAll("policyIds") String policyId1,
            @ForAll("policyIds") String policyId2,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String dbName,
            @ForAll("resourceNames") String tableName,
            @ForAll("roleNames") String roleName1,
            @ForAll("roleNames") String roleName2
    ) throws InternalException {
        String principalArn1 = "arn:aws:iam::" + accountId + ":role/" + roleName1;
        String principalArn2 = "arn:aws:iam::" + accountId + ":role/" + roleName2;
        String resourceArn = "arn:aws:glue:us-east-1:" + accountId + ":table/" + dbName + "/" + tableName;

        String cedarText = "@source(\"" + policyId1 + "\")\n"
                + "permit(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn1 + "\",\n"
                + "    action == DataCatalog::Action::\"SELECT\",\n"
                + "    resource == DataCatalog::Table::\"" + resourceArn + "\"\n"
                + ");\n"
                + "@source(\"" + policyId2 + "\")\n"
                + "permit(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn2 + "\",\n"
                + "    action == DataCatalog::Action::\"SELECT\",\n"
                + "    resource == DataCatalog::Table::\"" + resourceArn + "\"\n"
                + ");";

        CedarPolicySet original = CedarPolicySet.fromCedarString(cedarText);
        String serialized = original.toCedarString();
        CedarPolicySet roundTripped = CedarPolicySet.fromCedarString(serialized);

        assertEquals(original.getPermitCount(), roundTripped.getPermitCount(),
                "Permit count should be preserved after round-trip");
        assertEquals(original.getForbidCount(), roundTripped.getForbidCount(),
                "Forbid count should be preserved after round-trip");
        assertEquals(original.getPrincipals(), roundTripped.getPrincipals(),
                "Principals should be preserved after round-trip");
        assertEquals(new HashSet<>(original.getSourcePolicyIds()), new HashSet<>(roundTripped.getSourcePolicyIds()),
                "Source policy IDs should be preserved after round-trip");
    }

    @Property(tries = 100)
    void databaseLevelPolicyRoundTrip(
            @ForAll("policyIds") String policyId,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String dbName,
            @ForAll("databaseActions") String action
    ) throws InternalException {
        String principalArn = "arn:aws:iam::" + accountId + ":role/TestRole";
        String resourceArn = "arn:aws:glue:us-east-1:" + accountId + ":database/" + dbName;

        String cedarText = "@source(\"" + policyId + "\")\n"
                + "permit(\n"
                + "    principal == DataCatalog::Principal::\"" + principalArn + "\",\n"
                + "    action == DataCatalog::Action::\"" + action + "\",\n"
                + "    resource == DataCatalog::Database::\"" + resourceArn + "\"\n"
                + ");";

        CedarPolicySet original = CedarPolicySet.fromCedarString(cedarText);
        String serialized = original.toCedarString();
        CedarPolicySet roundTripped = CedarPolicySet.fromCedarString(serialized);

        assertEquals(original.getPermitCount(), roundTripped.getPermitCount(),
                "Permit count should be preserved after round-trip");
        assertEquals(original.getForbidCount(), roundTripped.getForbidCount(),
                "Forbid count should be preserved after round-trip");
        assertEquals(original.getPrincipals(), roundTripped.getPrincipals(),
                "Principals should be preserved after round-trip");
        assertEquals(new HashSet<>(original.getSourcePolicyIds()), new HashSet<>(roundTripped.getSourcePolicyIds()),
                "Source policy IDs should be preserved after round-trip");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> accountIds() {
        return Arbitraries.strings().numeric().ofLength(12);
    }

    @Provide
    Arbitrary<String> resourceNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('_')
                .ofMinLength(1)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> policyIds() {
        return Arbitraries.strings()
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(10);
    }

    @Provide
    Arbitrary<String> tableActions() {
        return Arbitraries.of(TABLE_ACTIONS);
    }

    @Provide
    Arbitrary<String> databaseActions() {
        return Arbitraries.of(DATABASE_ACTIONS);
    }

    @Provide
    Arbitrary<String> roleNames() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(20);
    }
}
