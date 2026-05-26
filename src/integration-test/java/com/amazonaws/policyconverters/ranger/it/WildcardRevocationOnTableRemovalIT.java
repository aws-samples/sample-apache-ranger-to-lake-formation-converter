package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.sync.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that when a table is removed from the Glue catalog (simulated by
 * shrinking CatalogResolver output), the wildcard policy's grant for that table
 * is REVOKED on the next sync cycle.
 */
public class WildcardRevocationOnTableRemovalIT extends DryRunPipelineIT {

    private final List<String> availableTables =
            new ArrayList<>(List.of("wc_table1", "wc_table2", "wc_table3"));

    @BeforeEach
    @Override
    void setUp(TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        rewireWithCustomCatalogResolver(new CatalogResolver(null) {
            @Override
            public List<String> expandDatabases(String pattern) {
                return List.of(pattern);
            }

            @Override
            public List<String> expandTables(String database, String tablePattern) {
                if (tablePattern.contains("*") || tablePattern.contains("?")) {
                    return new ArrayList<>(availableTables);
                }
                return List.of(tablePattern);
            }

            @Override
            public List<String> expandColumns(String database, String table, String colPattern) {
                return List.of(colPattern);
            }
        });
    }

    @Test
    void tableRemovedFromGlueCausesRevocationOnNextCycle() throws Exception {
        String wildcardPolicy = """
                {
                  "service": "lakeformation",
                  "name": "wildcard-revocation-test",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["wildcard_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["*"],            "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["etl_user"],
                      "groups": [], "roles": [], "conditions": [], "delegateAdmin": false
                    }
                  ]
                }
                """;

        // Cycle 1: 3 tables available
        createAndTrackPolicy(wildcardPolicy);
        triggerSync();

        List<String> cycle1Tables = getGrantedTablesForUser("wildcard_db", "etl_user");
        assertTrue(cycle1Tables.containsAll(List.of("wc_table1", "wc_table2", "wc_table3")),
                "Cycle 1: grants expected for all 3 tables. Found: " + cycle1Tables);

        // Simulate wc_table3 being dropped from Glue
        availableTables.remove("wc_table3");
        clearDryRunOutputs();

        // Cycle 2: wc_table3 gone — must produce a REVOKE for wc_table3
        triggerSync();

        List<DryRunOutput> cycle2Outputs = readDryRunOutputs();
        List<LFPermissionOperation> revokesForTable3 = cycle2Outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> "wildcard_db".equals(op.getResource().getDatabaseName()))
                .filter(op -> "wc_table3".equals(op.getResource().getTableName()))
                .filter(op -> op.getPrincipalArn().contains("etl_user"))
                .collect(Collectors.toList());

        assertFalse(revokesForTable3.isEmpty(),
                "wc_table3 was removed from the catalog — a REVOKE must be produced for etl_user. " +
                "Failing this means a removed table's permissions are never cleaned up in LF.");

        List<LFPermissionOperation> wrongRevokes = cycle2Outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> "wildcard_db".equals(op.getResource().getDatabaseName()))
                .filter(op -> List.of("wc_table1", "wc_table2").contains(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertTrue(wrongRevokes.isEmpty(),
                "Tables still in the catalog must not be revoked. Wrong revokes: " + wrongRevokes);
    }

    private List<String> getGrantedTablesForUser(String database, String userHint) throws Exception {
        return readDryRunOutputs().stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> database.equals(op.getResource().getDatabaseName()))
                .filter(op -> op.getPrincipalArn().contains(userHint))
                .map(op -> op.getResource().getTableName())
                .collect(Collectors.toList());
    }

    private void rewireWithCustomCatalogResolver(CatalogResolver resolver) throws Exception {
        if (syncService != null) {
            syncService.stop();
        }

        AwsContext awsContext = new AwsContext(TEST_REGION, TEST_ACCOUNT_ID, TEST_ACCOUNT_ID);
        RangerServiceAdapter lfAdapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("lakeformation", lfAdapter);

        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("analyst",    "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst");
        userMappings.put("etl_user",   "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user");
        userMappings.put("data_admin", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin");
        userMappings.put("viewer",     "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/viewer");
        StaticPrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMappings, Collections.emptyMap(), Collections.emptyMap()), null);

        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider cedarSchemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, resolver, gapReporter, cedarSchemaProvider);
        CedarToLFConverter cedarToLFConverter = new CedarToLFConverter(
                cedarSchemaProvider, gapReporter, null);

        RangerPlugin plugin = new RangerPlugin();
        syncService = new SyncService(
                plugin, rangerToCedarConverter, cedarToLFConverter,
                dryRunClient, gapReporter, null);

        syncService.start(new SyncConfig(null, null, null, null, null, null, null));
    }
}
