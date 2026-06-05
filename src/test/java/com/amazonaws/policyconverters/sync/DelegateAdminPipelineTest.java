package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RetryConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.Permission;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end regression test for the delegateAdmin→permissionsWithGrantOption pipeline.
 *
 * Uses REAL RangerToCedarConverter and CedarToLFConverter. Only the AWS SDK client is mocked.
 *
 * Catches the specific bug where RangerToCedarConverter dropped the @grantable annotation,
 * causing CedarToLFConverter to always produce grantable=false, which meant
 * LakeFormationClient.toBatchEntry() never set permissionsWithGrantOption.
 */
@ExtendWith(MockitoExtension.class)
class DelegateAdminPipelineTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String REGION = "us-east-1";

    @Mock
    private software.amazon.awssdk.services.lakeformation.LakeFormationClient awsSdkClient;

    private SyncService syncService;

    @BeforeEach
    void setUp() {
        AwsContext awsContext = new AwsContext(REGION, ACCOUNT_ID, ACCOUNT_ID);
        RangerServiceAdapter adapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("lakeformation", adapter);

        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":role/alice");
        PrincipalMappingConfig mappingConfig = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());

        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schema = new CedarSchemaProvider();

        RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                adapterRegistry,
                StaticPrincipalMapper.fromConfig(mappingConfig, null),
                new PassthroughCatalogResolver(),
                gapReporter,
                schema);

        CedarToLFConverter cedarToLFConverter = new CedarToLFConverter(schema, gapReporter, null);

        RetryConfig retryConfig = new RetryConfig(1, 0L, 1.0, 0L);
        LakeFormationClient lfClient = new LakeFormationClient(awsSdkClient, retryConfig);

        syncService = new SyncService(
                new RangerPlugin(), rangerToCedarConverter, cedarToLFConverter,
                lfClient, gapReporter, null);
        syncService.start(new SyncConfig(null, null, null, null, null, null, null));
    }

    @Test
    void delegateAdminTrueProducesPermissionsWithGrantOptionInSdkCall() {
        when(awsSdkClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build());

        RangerPolicy policy = buildTablePolicy("lakeformation", 1L, "db1", "tbl1");
        policy.setPolicyItems(Collections.singletonList(
                buildItem("alice", "select", true)));

        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(1L);
        sp.setPolicies(Collections.singletonList(policy));

        syncService.onPoliciesUpdated(sp);

        ArgumentCaptor<BatchGrantPermissionsRequest> captor =
                ArgumentCaptor.forClass(BatchGrantPermissionsRequest.class);
        verify(awsSdkClient).batchGrantPermissions(captor.capture());

        BatchGrantPermissionsRequest request = captor.getValue();
        assertFalse(request.entries().isEmpty(),
                "BatchGrantPermissionsRequest must contain at least one entry");
        List<Permission> grantOptions = request.entries().get(0).permissionsWithGrantOption();
        assertFalse(grantOptions == null || grantOptions.isEmpty(),
                "permissionsWithGrantOption must be non-empty when delegateAdmin=true — "
                + "the @grantable annotation must survive the full Ranger→Cedar→LF pipeline");
        assertTrue(grantOptions.contains(Permission.SELECT),
                "permissionsWithGrantOption must contain SELECT");
    }

    @Test
    void delegateAdminFalseProducesNoPermissionsWithGrantOption() {
        when(awsSdkClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build());

        RangerPolicy policy = buildTablePolicy("lakeformation", 2L, "db1", "tbl2");
        policy.setPolicyItems(Collections.singletonList(
                buildItem("alice", "select", false)));

        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(1L);
        sp.setPolicies(Collections.singletonList(policy));

        syncService.onPoliciesUpdated(sp);

        ArgumentCaptor<BatchGrantPermissionsRequest> captor =
                ArgumentCaptor.forClass(BatchGrantPermissionsRequest.class);
        verify(awsSdkClient).batchGrantPermissions(captor.capture());

        List<Permission> grantOptions = captor.getValue().entries().get(0).permissionsWithGrantOption();
        assertTrue(grantOptions == null || grantOptions.isEmpty(),
                "permissionsWithGrantOption must be empty when delegateAdmin=false — "
                + "non-grantable policies must not produce grant options");
    }

    private static RangerPolicy buildTablePolicy(String service, long id,
                                                  String database, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("test-delegate-admin-policy-" + id);
        policy.setService(service);
        policy.setPolicyType(0);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList(table));
        resources.put("table", tableRes);
        policy.setResources(resources);
        return policy;
    }

    private static RangerPolicyItem buildItem(String user, String accessType, boolean delegateAdmin) {
        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType(accessType);
        access.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(access));
        item.setUsers(Collections.singletonList(user));
        item.setDelegateAdmin(delegateAdmin);
        return item;
    }
}
