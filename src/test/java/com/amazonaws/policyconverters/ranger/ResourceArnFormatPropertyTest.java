package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import net.jqwik.api.*;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 3: Resource ARN Format Consistency

/**
 * Property-based test verifying that all adapters (Hive, Presto, Trino, and
 * LakeFormation as reference) produce ARNs matching the Glue ARN format
 * {@code arn:aws:glue:{region}:{account}:{resourceType}/{path}} and that
 * all adapters produce identical ARNs for the same inputs.
 *
 * **Validates: Requirements 3.3, 4.3, 5.3**
 */
@Tag("multi-ranger-plugin-support")
class ResourceArnFormatPropertyTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final AwsContext AWS_CONTEXT = new AwsContext(REGION, ACCOUNT_ID, ACCOUNT_ID);

    private static final Pattern DATABASE_ARN_PATTERN = Pattern.compile(
            "^arn:aws:glue:[a-z0-9-]+:\\d{12}:database/.+$");
    private static final Pattern TABLE_ARN_PATTERN = Pattern.compile(
            "^arn:aws:glue:[a-z0-9-]+:\\d{12}:table/.+/.+$");
    private static final Pattern COLUMN_ARN_PATTERN = Pattern.compile(
            "^arn:aws:glue:[a-z0-9-]+:\\d{12}:column/.+/.+/.+$");

    private final RangerServiceAdapter lfAdapter = new RangerServiceAdapter(AWS_CONTEXT);
    private final HiveServiceAdapter hiveAdapter = new HiveServiceAdapter(AWS_CONTEXT);
    private final PrestoServiceAdapter prestoAdapter = new PrestoServiceAdapter(AWS_CONTEXT, "awsdatacatalog");
    private final TrinoServiceAdapter trinoAdapter = new TrinoServiceAdapter(AWS_CONTEXT, "awsdatacatalog");

    // ---- Database ARN format and consistency ----

    @Property(tries = 100)
    void allAdaptersProduceDatabaseArnsMatchingGlueFormat(
            @ForAll("resourceNames") String database
    ) {
        String lfArn = lfAdapter.buildDatabaseArn(database);
        String hiveArn = hiveAdapter.buildDatabaseArn(database);
        String prestoArn = prestoAdapter.buildDatabaseArn(database);
        String trinoArn = trinoAdapter.buildDatabaseArn(database);

        // Verify format
        assertTrue(DATABASE_ARN_PATTERN.matcher(lfArn).matches(),
                "LakeFormation database ARN does not match pattern: " + lfArn);
        assertTrue(DATABASE_ARN_PATTERN.matcher(hiveArn).matches(),
                "Hive database ARN does not match pattern: " + hiveArn);
        assertTrue(DATABASE_ARN_PATTERN.matcher(prestoArn).matches(),
                "Presto database ARN does not match pattern: " + prestoArn);
        assertTrue(DATABASE_ARN_PATTERN.matcher(trinoArn).matches(),
                "Trino database ARN does not match pattern: " + trinoArn);

        // Verify consistency across adapters
        assertEquals(lfArn, hiveArn,
                "Hive database ARN differs from LakeFormation for database='" + database + "'");
        assertEquals(lfArn, prestoArn,
                "Presto database ARN differs from LakeFormation for database='" + database + "'");
        assertEquals(lfArn, trinoArn,
                "Trino database ARN differs from LakeFormation for database='" + database + "'");
    }

    // ---- Table ARN format and consistency ----

    @Property(tries = 100)
    void allAdaptersProduceTableArnsMatchingGlueFormat(
            @ForAll("resourceNames") String database,
            @ForAll("resourceNames") String table
    ) {
        String lfArn = lfAdapter.buildTableArn(database, table);
        String hiveArn = hiveAdapter.buildTableArn(database, table);
        String prestoArn = prestoAdapter.buildTableArn(database, table);
        String trinoArn = trinoAdapter.buildTableArn(database, table);

        // Verify format
        assertTrue(TABLE_ARN_PATTERN.matcher(lfArn).matches(),
                "LakeFormation table ARN does not match pattern: " + lfArn);
        assertTrue(TABLE_ARN_PATTERN.matcher(hiveArn).matches(),
                "Hive table ARN does not match pattern: " + hiveArn);
        assertTrue(TABLE_ARN_PATTERN.matcher(prestoArn).matches(),
                "Presto table ARN does not match pattern: " + prestoArn);
        assertTrue(TABLE_ARN_PATTERN.matcher(trinoArn).matches(),
                "Trino table ARN does not match pattern: " + trinoArn);

        // Verify consistency across adapters
        assertEquals(lfArn, hiveArn,
                "Hive table ARN differs from LakeFormation for db='" + database + "', table='" + table + "'");
        assertEquals(lfArn, prestoArn,
                "Presto table ARN differs from LakeFormation for db='" + database + "', table='" + table + "'");
        assertEquals(lfArn, trinoArn,
                "Trino table ARN differs from LakeFormation for db='" + database + "', table='" + table + "'");
    }

    // ---- Column ARN format and consistency ----

    @Property(tries = 100)
    void allAdaptersProduceColumnArnsMatchingGlueFormat(
            @ForAll("resourceNames") String database,
            @ForAll("resourceNames") String table,
            @ForAll("resourceNames") String column
    ) {
        String lfArn = lfAdapter.buildColumnArn(database, table, column);
        String hiveArn = hiveAdapter.buildColumnArn(database, table, column);
        String prestoArn = prestoAdapter.buildColumnArn(database, table, column);
        String trinoArn = trinoAdapter.buildColumnArn(database, table, column);

        // Verify format
        assertTrue(COLUMN_ARN_PATTERN.matcher(lfArn).matches(),
                "LakeFormation column ARN does not match pattern: " + lfArn);
        assertTrue(COLUMN_ARN_PATTERN.matcher(hiveArn).matches(),
                "Hive column ARN does not match pattern: " + hiveArn);
        assertTrue(COLUMN_ARN_PATTERN.matcher(prestoArn).matches(),
                "Presto column ARN does not match pattern: " + prestoArn);
        assertTrue(COLUMN_ARN_PATTERN.matcher(trinoArn).matches(),
                "Trino column ARN does not match pattern: " + trinoArn);

        // Verify consistency across adapters
        assertEquals(lfArn, hiveArn,
                "Hive column ARN differs from LakeFormation for db='" + database + "', table='" + table + "', col='" + column + "'");
        assertEquals(lfArn, prestoArn,
                "Presto column ARN differs from LakeFormation for db='" + database + "', table='" + table + "', col='" + column + "'");
        assertEquals(lfArn, trinoArn,
                "Trino column ARN differs from LakeFormation for db='" + database + "', table='" + table + "', col='" + column + "'");
    }

    // ---- ARN contains expected region and account ----

    @Property(tries = 100)
    void arnsContainConfiguredRegionAndAccount(
            @ForAll("resourceNames") String database,
            @ForAll("resourceNames") String table,
            @ForAll("resourceNames") String column
    ) {
        String dbArn = hiveAdapter.buildDatabaseArn(database);
        String tableArn = prestoAdapter.buildTableArn(database, table);
        String colArn = trinoAdapter.buildColumnArn(database, table, column);

        assertTrue(dbArn.contains(REGION), "Database ARN should contain region: " + dbArn);
        assertTrue(dbArn.contains(ACCOUNT_ID), "Database ARN should contain account: " + dbArn);
        assertTrue(tableArn.contains(REGION), "Table ARN should contain region: " + tableArn);
        assertTrue(tableArn.contains(ACCOUNT_ID), "Table ARN should contain account: " + tableArn);
        assertTrue(colArn.contains(REGION), "Column ARN should contain region: " + colArn);
        assertTrue(colArn.contains(ACCOUNT_ID), "Column ARN should contain account: " + colArn);
    }

    // --- Arbitraries ---

    /** Generates alphanumeric resource names of reasonable length (1-30 chars). */
    @Provide
    Arbitrary<String> resourceNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-')
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(s -> s.matches("[a-zA-Z0-9_-]+"));
    }
}
