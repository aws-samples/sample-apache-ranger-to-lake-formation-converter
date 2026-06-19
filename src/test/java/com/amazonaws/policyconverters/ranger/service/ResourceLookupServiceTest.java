package com.amazonaws.policyconverters.ranger.service;
import com.amazonaws.policyconverters.ranger.service.ResourceLookupService;

import com.amazonaws.policyconverters.ranger.CatalogResolver;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTableResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResourceLookupServiceTest {

    private GlueClient mockGlueClient;
    private ResourceLookupService service;

    @BeforeEach
    void setUp() {
        mockGlueClient = mock(GlueClient.class);
        // Factory that always returns our mock, ignoring region/credentials
        ResourceLookupService.GlueClientFactory factory =
                (region, credentialsProvider) -> mockGlueClient;
        service = new ResourceLookupService(factory);
    }

    // --- validateConfig tests ---

    @Test
    void validateConfig_missingRegion_returnsFalse() throws Exception {
        service.setConfigs(Collections.emptyMap());

        Map<String, Object> result = service.validateConfig();

        assertEquals(false, result.get("connectivityStatus"));
        assertTrue(((String) result.get("message")).contains("aws.region"));
    }

    @Test
    void validateConfig_emptyRegion_returnsFalse() throws Exception {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "  ");
        service.setConfigs(configs);

        Map<String, Object> result = service.validateConfig();

        assertEquals(false, result.get("connectivityStatus"));
    }

    @Test
    void validateConfig_validRegion_glueSucceeds_returnsTrue() throws Exception {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        configs.put("aws.access.key", "EXAMPLE_ACCESS_KEY_ID");
        configs.put("aws.secret.key", "EXAMPLE_SECRET_ACCESS_KEY");
        service.setConfigs(configs);

        when(mockGlueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(Collections.emptyList())
                        .build());

        Map<String, Object> result = service.validateConfig();

        assertEquals(true, result.get("connectivityStatus"));
        assertEquals("Connection successful", result.get("message"));
        verify(mockGlueClient).getDatabases(any(GetDatabasesRequest.class));
        verify(mockGlueClient).close();
    }

    @Test
    void validateConfig_glueThrowsException_returnsFalse() throws Exception {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        service.setConfigs(configs);

        when(mockGlueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenThrow(new RuntimeException("Access denied"));

        Map<String, Object> result = service.validateConfig();

        assertEquals(false, result.get("connectivityStatus"));
        assertTrue(((String) result.get("message")).contains("Access denied"));
    }

    // --- lookupResource tests ---

    @Test
    void lookupResource_nullContext_returnsEmpty() throws Exception {
        service.setConfigs(configWithRegion());

        List<String> result = service.lookupResource(null);

        assertTrue(result.isEmpty());
    }

    @Test
    void lookupResource_nullResourceName_returnsEmpty() throws Exception {
        service.setConfigs(configWithRegion());
        ResourceLookupContext ctx = new ResourceLookupContext();

        List<String> result = service.lookupResource(ctx);

        assertTrue(result.isEmpty());
    }

    @Test
    void lookupResource_missingRegion_returnsEmpty() throws Exception {
        service.setConfigs(Collections.emptyMap());
        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("database");

        List<String> result = service.lookupResource(ctx);

        assertTrue(result.isEmpty());
    }

    @Test
    void lookupResource_databases_returnsMatchingDatabases() throws Exception {
        service.setConfigs(configWithRegion());

        when(mockGlueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("analytics").build(),
                                Database.builder().name("production").build(),
                                Database.builder().name("analytics_staging").build()
                        )
                        .build());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("database");
        ctx.setUserInput("ana");

        List<String> result = service.lookupResource(ctx);

        assertEquals(2, result.size());
        assertTrue(result.contains("analytics"));
        assertTrue(result.contains("analytics_staging"));
    }

    @Test
    void lookupResource_databases_noUserInput_returnsAll() throws Exception {
        service.setConfigs(configWithRegion());

        when(mockGlueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("db1").build(),
                                Database.builder().name("db2").build()
                        )
                        .build());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("database");

        List<String> result = service.lookupResource(ctx);

        assertEquals(2, result.size());
    }

    @Test
    void lookupResource_tables_returnsMatchingTables() throws Exception {
        service.setConfigs(configWithRegion());

        when(mockGlueClient.getTables(any(GetTablesRequest.class)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(
                                Table.builder().name("events").build(),
                                Table.builder().name("events_archive").build(),
                                Table.builder().name("users").build()
                        )
                        .build());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("table");
        ctx.setUserInput("ev");
        Map<String, List<String>> resources = new HashMap<>();
        resources.put("database", Collections.singletonList("analytics"));
        ctx.setResources(resources);

        List<String> result = service.lookupResource(ctx);

        assertEquals(2, result.size());
        assertTrue(result.contains("events"));
        assertTrue(result.contains("events_archive"));
    }

    @Test
    void lookupResource_tables_noDatabaseSelected_returnsEmpty() throws Exception {
        service.setConfigs(configWithRegion());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("table");
        ctx.setUserInput("ev");
        ctx.setResources(Collections.emptyMap());

        List<String> result = service.lookupResource(ctx);

        assertTrue(result.isEmpty());
    }

    @Test
    void lookupResource_columns_returnsMatchingColumns() throws Exception {
        service.setConfigs(configWithRegion());

        when(mockGlueClient.getTable(any(GetTableRequest.class)))
                .thenReturn(GetTableResponse.builder()
                        .table(Table.builder()
                                .name("events")
                                .storageDescriptor(StorageDescriptor.builder()
                                        .columns(
                                                Column.builder().name("user_id").type("string").build(),
                                                Column.builder().name("user_name").type("string").build(),
                                                Column.builder().name("event_type").type("string").build()
                                        )
                                        .build())
                                .build())
                        .build());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("column");
        ctx.setUserInput("user");
        Map<String, List<String>> resources = new HashMap<>();
        resources.put("database", Collections.singletonList("analytics"));
        resources.put("table", Collections.singletonList("events"));
        ctx.setResources(resources);

        List<String> result = service.lookupResource(ctx);

        assertEquals(2, result.size());
        assertTrue(result.contains("user_id"));
        assertTrue(result.contains("user_name"));
    }

    @Test
    void lookupResource_columns_noTableSelected_returnsEmpty() throws Exception {
        service.setConfigs(configWithRegion());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("column");
        ctx.setUserInput("col");
        Map<String, List<String>> resources = new HashMap<>();
        resources.put("database", Collections.singletonList("analytics"));
        ctx.setResources(resources);

        List<String> result = service.lookupResource(ctx);

        assertTrue(result.isEmpty());
    }

    @Test
    void lookupResource_unknownResourceType_returnsEmpty() throws Exception {
        service.setConfigs(configWithRegion());

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("unknown_type");

        List<String> result = service.lookupResource(ctx);

        assertTrue(result.isEmpty());
    }

    @Test
    void lookupResource_glueThrowsException_returnsEmpty() throws Exception {
        service.setConfigs(configWithRegion());

        when(mockGlueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenThrow(new RuntimeException("Service unavailable"));

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("database");

        List<String> result = service.lookupResource(ctx);

        assertTrue(result.isEmpty());
    }

    // --- buildCredentialsProvider tests ---

    @Test
    void buildCredentialsProvider_staticCredentials() {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        configs.put("aws.access.key", "EXAMPLE_ACCESS_KEY_ID");
        configs.put("aws.secret.key", "EXAMPLE_SECRET_ACCESS_KEY");

        AwsCredentialsProvider provider = service.buildCredentialsProvider(configs);

        assertTrue(provider instanceof StaticCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_defaultChain_whenNoCreds() {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");

        AwsCredentialsProvider provider = service.buildCredentialsProvider(configs);

        assertTrue(provider instanceof DefaultCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_roleArn_usesStsAssumeRole() {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        configs.put("aws.role.arn", "arn:aws:iam::123456789012:role/TestRole");

        AwsCredentialsProvider provider = service.buildCredentialsProvider(configs);

        assertTrue(provider instanceof StsAssumeRoleCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_roleArnWithStaticCreds_usesStsAssumeRole() {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        configs.put("aws.access.key", "EXAMPLE_ACCESS_KEY_ID");
        configs.put("aws.secret.key", "EXAMPLE_SECRET_ACCESS_KEY");
        configs.put("aws.role.arn", "arn:aws:iam::123456789012:role/TestRole");

        AwsCredentialsProvider provider = service.buildCredentialsProvider(configs);

        // Role ARN takes priority over static creds
        assertTrue(provider instanceof StsAssumeRoleCredentialsProvider);
    }

    // --- helper ---

    private Map<String, String> configWithRegion() {
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        configs.put("aws.access.key", "EXAMPLE_ACCESS_KEY_ID");
        configs.put("aws.secret.key", "EXAMPLE_SECRET_ACCESS_KEY");
        return configs;
    }
}
