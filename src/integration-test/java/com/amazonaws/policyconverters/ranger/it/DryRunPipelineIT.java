package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.cedar.AwsContext;
import com.amazonaws.policyconverters.lakeformation.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.client.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.model.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.reporter.GapReporter;
import com.amazonaws.policyconverters.ranger.catalog.CatalogResolver;
import com.amazonaws.policyconverters.ranger.cedar.RangerLFServiceAdapter;
import com.amazonaws.policyconverters.ranger.cedar.RangerToCedarConverter;
import com.amazonaws.policyconverters.ranger.mapper.PrincipalMapper;
import com.amazonaws.policyconverters.ranger.sync.LakeFormationPlugin;
import com.amazonaws.policyconverters.ranger.sync.SyncService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for dry-run pipeline integration tests.
 *
 * <p>Handles Ranger service instance setup, per-test output directory creation,
 * pipeline wiring, and cleanup of created policies and output files.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class DryRunPipelineIT {

    private static final Logger LOG = LoggerFactory.getLogger(DryRunPipelineIT.class);
    private static final String DEFAULT_RANGER_URL = "http://localhost:6080";
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASSWORD = "rangerR0cks!";
    protected static final String TEST_ACCOUNT_ID = "123456789012";
    protected static final String TEST_REGION = "us-east-1";

    protected static String rangerAdminUrl;
    protected RangerPolicyRestClient policyClient;
    protected Path outputDirectory;
    protected List<Integer> createdPolicyIds;
    protected SyncService syncService;
    protected DryRunLakeFormationClient dryRunClient;
    protected ObjectMapper objectMapper;
    protected IntegrationTestAuditLog auditLog;

    /** Shared audit log instance per test class — written to disk in @AfterAll. */
    private static IntegrationTestAuditLog sharedAuditLog;

    @BeforeAll
    static void ensureServiceInstance(TestInfo testInfo) throws Exception {
        // Initialize the shared audit log for this test class
        String className = testInfo.getTestClass().map(Class::getSimpleName).orElse("Unknown");
        sharedAuditLog = new IntegrationTestAuditLog(className);

        rangerAdminUrl = System.getProperty("ranger.admin.url", DEFAULT_RANGER_URL);

        // Verify lakeformation service definition exists
        HttpURLConnection conn = openConnection(
                rangerAdminUrl + "/service/public/v2/api/servicedef/name/lakeformation", "GET");
        int status = conn.getResponseCode();
        String serviceDefBody = (status == 200) ? readStream(conn.getInputStream()) : "";
        conn.disconnect();
        assertTrue(status == 200,
                "lakeformation service definition must exist (run ServiceDefInstallIT first); HTTP " + status);

        // If the service def uses a custom implClass that isn't available in the Docker
        // container, update it to a built-in Ranger class so service instance creation works
        if (serviceDefBody.contains("LakeFormationResourceLookupService")) {
            String updatedDef = serviceDefBody.replaceAll(
                    "com\\.amazonaws\\.policyconverters\\.ranger\\.service\\.LakeFormationResourceLookupService",
                    "org.apache.ranger.services.tag.RangerServiceTag");
            // Extract the service def ID
            int idIdx = updatedDef.indexOf("\"id\":");
            if (idIdx >= 0) {
                int idStart = idIdx + 5;
                while (idStart < updatedDef.length() && Character.isWhitespace(updatedDef.charAt(idStart))) idStart++;
                int idEnd = idStart;
                while (idEnd < updatedDef.length() && Character.isDigit(updatedDef.charAt(idEnd))) idEnd++;
                String defId = updatedDef.substring(idStart, idEnd);

                conn = openConnection(
                        rangerAdminUrl + "/service/public/v2/api/servicedef/" + defId, "PUT");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(updatedDef.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                int updateStatus = conn.getResponseCode();
                conn.disconnect();
                LOG.info("Updated service def implClass for Docker compatibility (HTTP {})", updateStatus);
            }
        }

        // Check if service instance already exists
        conn = openConnection(
                rangerAdminUrl + "/service/public/v2/api/service/name/lakeformation", "GET");
        status = conn.getResponseCode();
        conn.disconnect();

        if (status != 200) {
            // Create the service instance
            String serviceJson = "{"
                    + "\"name\":\"lakeformation\","
                    + "\"type\":\"lakeformation\","
                    + "\"configs\":{"
                    + "\"aws.region\":\"" + TEST_REGION + "\","
                    + "\"aws.catalog.id\":\"" + TEST_ACCOUNT_ID + "\""
                    + "}"
                    + "}";
            conn = openConnection(
                    rangerAdminUrl + "/service/public/v2/api/service", "POST");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(serviceJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            int createStatus = conn.getResponseCode();
            String createBody = readStream(
                    createStatus >= 400 ? conn.getErrorStream() : conn.getInputStream());
            conn.disconnect();
            assertTrue(createStatus == 200 || createStatus == 409,
                    "Failed to create lakeformation service instance: HTTP " + createStatus + " - " + createBody);
            LOG.info("Created lakeformation service instance (HTTP {})", createStatus);
        } else {
            LOG.info("Reusing existing lakeformation service instance");
        }
    }

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        // Begin audit log entry for this test method
        auditLog = sharedAuditLog;
        String methodName = testInfo.getTestMethod().map(m -> m.getName()).orElse("unknown");
        auditLog.beginTest(methodName);

        outputDirectory = Files.createTempDirectory("dryrun-it-");
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        policyClient = new RangerPolicyRestClient(rangerAdminUrl, AUTH_USER, AUTH_PASSWORD);
        createdPolicyIds = new ArrayList<>();

        // Wire up the dry-run pipeline
        dryRunClient = new DryRunLakeFormationClient(outputDirectory, objectMapper);

        AwsContext awsContext = new AwsContext(TEST_REGION, TEST_ACCOUNT_ID, TEST_ACCOUNT_ID);
        RangerLFServiceAdapter lfAdapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("lakeformation", lfAdapter);

        // Principal mappings for test users
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("analyst", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst");
        userMappings.put("etl_user", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user");
        userMappings.put("data_admin", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin");
        userMappings.put("viewer", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/viewer");
        PrincipalMappingConfig principalConfig = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(principalConfig);

        // Passthrough CatalogResolver — returns input as-is (no wildcard expansion needed)
        CatalogResolver catalogResolver = new CatalogResolver(null) {
            @Override
            public List<String> expandDatabases(String pattern) {
                return Collections.singletonList(pattern);
            }

            @Override
            public List<String> expandTables(String database, String pattern) {
                return Collections.singletonList(pattern);
            }

            @Override
            public List<String> expandColumns(String database, String table, String pattern) {
                return Collections.singletonList(pattern);
            }
        };

        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider cedarSchemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, catalogResolver, gapReporter, cedarSchemaProvider);
        CedarToLFConverter cedarToLFConverter = new CedarToLFConverter(
                cedarSchemaProvider, gapReporter, null);

        LakeFormationPlugin plugin = new LakeFormationPlugin();
        syncService = new SyncService(
                plugin, rangerToCedarConverter, cedarToLFConverter,
                dryRunClient, gapReporter, null);

        // Start the sync service so onPoliciesUpdated is accepted
        SyncConfig config = new SyncConfig(null, null, null, null, null, null, null);
        syncService.start(config);
    }

    @AfterEach
    void tearDown() {
        // End audit log entry for this test method
        if (auditLog != null) {
            auditLog.endTest();
        }

        // Stop the sync service
        if (syncService != null && syncService.isRunning()) {
            syncService.stop();
        }

        // Delete created policies (log warning on failure, don't fail the test)
        if (createdPolicyIds != null) {
            for (int policyId : createdPolicyIds) {
                try {
                    policyClient.deletePolicy(policyId);
                } catch (Exception e) {
                    LOG.warn("Failed to delete policy {}: {}", policyId, e.getMessage());
                }
            }
        }

        // Delete output files (log warning on failure, don't fail the test)
        if (outputDirectory != null) {
            try {
                File[] files = outputDirectory.toFile().listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.delete()) {
                            LOG.warn("Failed to delete output file: {}", f);
                        }
                    }
                }
                if (!outputDirectory.toFile().delete()) {
                    LOG.warn("Failed to delete output directory: {}", outputDirectory);
                }
            } catch (Exception e) {
                LOG.warn("Error during output cleanup: {}", e.getMessage());
            }
        }
    }

    // ---- Helper methods ----

    @AfterAll
    static void flushAuditLog() {
        if (sharedAuditLog != null) {
            try {
                sharedAuditLog.flush();
            } catch (Exception e) {
                LOG.warn("Failed to write audit log: {}", e.getMessage());
            }
        }
    }

    /**
     * Create a policy via the REST client and track it for cleanup.
     */
    protected int createAndTrackPolicy(String policyJson) {
        int policyId = policyClient.createPolicy(policyJson);
        createdPolicyIds.add(policyId);
        if (auditLog != null) {
            auditLog.logRangerCreate(policyId, policyJson);
        }
        return policyId;
    }

    /**
     * Trigger a sync cycle by fetching policies from Ranger Admin and running
     * the full pipeline in dry-run mode. Logs the resulting LF operations to the audit log.
     */
    protected void triggerSync() throws Exception {
        // Fetch current policies from Ranger Admin
        String endpoint = rangerAdminUrl + "/service/public/v2/api/service/lakeformation/policy";
        HttpURLConnection conn = openConnection(endpoint, "GET");
        int status = conn.getResponseCode();
        assertEquals(200, status, "Failed to fetch policies from Ranger Admin");

        String responseBody = readStream(conn.getInputStream());
        conn.disconnect();

        List<RangerPolicy> policies = objectMapper.readValue(responseBody,
                new TypeReference<List<RangerPolicy>>() {});

        // Build ServicePolicies envelope
        ServicePolicies servicePolicies = new ServicePolicies();
        servicePolicies.setServiceName("lakeformation");
        servicePolicies.setPolicies(policies);
        servicePolicies.setPolicyVersion(System.currentTimeMillis());

        // Run the pipeline
        syncService.onPoliciesUpdated(servicePolicies);

        // Log the sync result to the audit log
        if (auditLog != null) {
            auditLog.logSyncResult(readDryRunOutputs());
        }
    }

    /**
     * Update a policy and log the action to the audit log.
     */
    protected void updatePolicy(int policyId, String policyJson) {
        policyClient.updatePolicy(policyId, policyJson);
        if (auditLog != null) {
            auditLog.logRangerUpdate(policyId, policyJson);
        }
    }

    /**
     * Delete a policy and log the action to the audit log.
     */
    protected void deletePolicyAndUntrack(int policyId) {
        policyClient.deletePolicy(policyId);
        createdPolicyIds.remove(Integer.valueOf(policyId));
        if (auditLog != null) {
            auditLog.logRangerDelete(policyId);
        }
    }

    /**
     * Read and parse all dry-run output files from the output directory.
     */
    protected List<DryRunOutput> readDryRunOutputs() throws Exception {
        File[] files = outputDirectory.toFile().listFiles(
                (dir, name) -> name.startsWith("dry-run-") && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        List<DryRunOutput> outputs = new ArrayList<>();
        for (File f : files) {
            outputs.add(objectMapper.readValue(f, DryRunOutput.class));
        }
        return outputs;
    }

    /**
     * Clear dry-run output files between sync cycles in the same test.
     */
    protected void clearDryRunOutputs() {
        File[] files = outputDirectory.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static HttpURLConnection openConnection(String endpoint, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        String credentials = AUTH_USER + ":" + AUTH_PASSWORD;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encoded);
        if ("POST".equals(method) || "PUT".equals(method)) {
            conn.setDoOutput(true);
        }
        return conn;
    }

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }
}
