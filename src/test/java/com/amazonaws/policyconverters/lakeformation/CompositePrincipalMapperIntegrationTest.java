package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that CompositePrincipalMapper integrates correctly with RangerToCedarConverter:
 * - A principal in the static map resolves to the static ARN.
 * - A principal not in the static map resolves via the IDC delegate.
 * - A principal in neither map produces a gap (no Cedar statement).
 */
class CompositePrincipalMapperIntegrationTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String ALICE_STATIC_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/alice-static";
    private static final String BOB_IDC_ARN = "arn:aws:identitystore::" + ACCOUNT_ID + ":user/bob-idc-id";

    @Test
    void staticHit_producesStaticArn() throws Exception {
        RangerToCedarConverter converter = buildConverter();
        RangerPolicy policy = singleUserPolicy("alice", "select", "db1", "t1");

        CedarPolicySet policySet = converter.convert(List.of(policy));
        String cedar = policySet.toCedarString();

        assertTrue(cedar.contains(ALICE_STATIC_ARN),
                "alice is in the static map — Cedar must use the static ARN");
    }

    @Test
    void idcFallback_producesIdcArn() throws Exception {
        RangerToCedarConverter converter = buildConverter();
        RangerPolicy policy = singleUserPolicy("bob", "select", "db1", "t1");

        CedarPolicySet policySet = converter.convert(List.of(policy));
        String cedar = policySet.toCedarString();

        assertTrue(cedar.contains(BOB_IDC_ARN),
                "bob is not in the static map — Cedar must fall back to the IDC ARN");
    }

    @Test
    void bothMiss_producesNoStatement() throws Exception {
        GapReporter gapReporter = new GapReporter();
        RangerToCedarConverter converter = buildConverter(gapReporter);
        RangerPolicy policy = singleUserPolicy("charlie", "select", "db1", "t1");

        CedarPolicySet policySet = converter.convert(List.of(policy));

        assertEquals(0, policySet.getPermitCount(),
                "charlie is in neither map — no Cedar statement should be produced");
    }

    private RangerToCedarConverter buildConverter() {
        return buildConverter(new GapReporter());
    }

    private RangerToCedarConverter buildConverter(GapReporter gapReporter) {
        PrincipalMappingConfig staticConfig = new PrincipalMappingConfig(
                Map.of("alice", ALICE_STATIC_ARN),
                Collections.emptyMap(),
                Collections.emptyMap());
        PrincipalMapper staticMapper = StaticPrincipalMapper.fromConfig(staticConfig, null);

        PrincipalMapper idcMapper = new PrincipalMapper() {
            @Override public Optional<String> resolveUser(String name) {
                return "bob".equals(name) ? Optional.of(BOB_IDC_ARN) : Optional.empty();
            }
            @Override public Optional<String> resolveGroup(String name) { return Optional.empty(); }
            @Override public Optional<String> resolveRole(String name) { return Optional.empty(); }
        };

        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(staticMapper, idcMapper), null);

        AwsContext awsContext = new AwsContext("us-east-1", ACCOUNT_ID, ACCOUNT_ID);
        RangerServiceAdapter adapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        CedarSchemaProvider schema = new CedarSchemaProvider();
        return new RangerToCedarConverter(
                registry, composite, new PassthroughCatalogResolver(), gapReporter, schema);
    }

    private static RangerPolicy singleUserPolicy(String user, String access,
                                                  String database, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test-policy");
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList(table));
        resources.put("table", tableRes);
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess a = new RangerPolicyItemAccess();
        a.setType(access);
        a.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(a));
        item.setUsers(Collections.singletonList(user));
        item.setDelegateAdmin(false);
        policy.setPolicyItems(Collections.singletonList(item));
        return policy;
    }
}
