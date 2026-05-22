package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToS3AccessGrantsConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.S3AccessGrantsConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.EmrfsServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.CreateAccessGrantRequest;
import software.amazon.awssdk.services.s3control.model.CreateAccessGrantResponse;
import software.amazon.awssdk.services.s3control.model.GranteeType;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsResponse;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse;
import software.amazon.awssdk.services.s3control.model.Permission;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for the EMRFS → S3 Access Grants pipeline.
 *
 * <p>Wires the full in-process conversion pipeline:
 * <ol>
 *   <li>Fetches EMRFS policies from a live Ranger Admin via REST.</li>
 *   <li>Runs the Cedar conversion pipeline (RangerToCedarConverter with EmrfsServiceAdapter,
 *       then CedarToS3AccessGrantsConverter).</li>
 *   <li>Applies the resulting operations via {@link S3AccessGrantsClient} backed by a
 *       Mockito-mocked {@link S3ControlClient} — no LocalStack required.</li>
 * </ol>
 *
 * <p>This is the same technique used in
 * {@link com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClientTest}, extended
 * to cover a full pipeline cycle instead of just the client in isolation.
 *
 * <h2>Pre-requisites</h2>
 * A running Ranger Admin at {@code http://localhost:6080} (or the URL specified via
 * {@code -Dranger.admin.url}) with the {@code amazon-emr-emrfs} service definition installed.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@link #grantCreated_whenPrefixIsRegistered_readWritePermission()} — EMRFS policy
 *       with GetObject + PutObject for a registered S3 prefix produces a
 *       {@code CreateAccessGrant} call with the correct principal ARN, S3 prefix,
 *       and {@code READWRITE} permission.</li>
 *   <li>{@link #noGrantCreated_whenPrefixIsUnregistered_deadLetterWritten()} — EMRFS policy
 *       for an S3 prefix not covered by any registered location produces no
 *       {@code CreateAccessGrant} call and writes a dead-letter entry.</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EmrfsS3AccessGrantsIT {

    // ---- Configuration ----
    private static final String DEFAULT_RANGER_URL = "http://localhost:6080";
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASSWORD = "rangerR0cks!";
    private static final String TEST_ACCOUNT_ID = "123456789012";
    private static final String TEST_REGION = "us-east-1";
    private static final String INSTANCE_ARN =
            "arn:aws:s3:us-east-1:" + TEST_ACCOUNT_ID + ":access-grants/default";

    /** The S3 bucket scope registered as a location in the Access Grants instance. */
    private static final String REGISTERED_LOCATION = "s3://test-emrfs-bucket/";
    private static final String REGISTERED_LOCATION_ID = "loc-emrfs-it-001";

    /** Ranger user that has an entry in the principal mapping. */
    private static final String RANGER_USER = "alice";
    private static final String PRINCIPAL_ARN = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":user/alice";

    // ---- Shared state ----
    private static String rangerAdminUrl;

    // ---- Per-test state ----
    private RangerPolicyRestClient policyClient;
    private List<Integer> createdPolicyIds;
    private ObjectMapper objectMapper;

    /** Fresh Mockito mock for each test — reset between tests. */
    private S3ControlClient mockS3Control;

    /** Captures dead-letter log writes during each test. */
    private StringWriter deadLetterOutput;
    private DeadLetterLogger deadLetterLogger;

    // -----------------------------------------------------------------------
    // @BeforeAll — ensure the EMRFS service definition and instance exist
    // -----------------------------------------------------------------------

    @BeforeAll
    static void ensureEmrfsServiceInstance(TestInfo testInfo) throws Exception {
        rangerAdminUrl = System.getProperty("ranger.admin.url", DEFAULT_RANGER_URL);

        // Verify the amazon-emr-emrfs service definition is installed
        HttpURLConnection conn = openConnection(
                rangerAdminUrl + "/service/public/v2/api/servicedef/name/amazon-emr-emrfs", "GET");
        int status = conn.getResponseCode();
        conn.disconnect();
        assertTrue(status == 200,
                "amazon-emr-emrfs service definition must be installed before running this test; "
                        + "HTTP " + status);

        // Create the service instance if it does not already exist
        conn = openConnection(
                rangerAdminUrl + "/service/public/v2/api/service/name/amazon-emr-emrfs", "GET");
        status = conn.getResponseCode();
        conn.disconnect();

        if (status != 200) {
            String serviceJson = "{"
                    + "\"name\":\"amazon-emr-emrfs\","
                    + "\"type\":\"amazon-emr-emrfs\","
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
                    "Failed to create amazon-emr-emrfs service instance: HTTP "
                            + createStatus + " - " + createBody);
        }
    }

    // -----------------------------------------------------------------------
    // @BeforeEach — create fresh mock and shared state per test
    // -----------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        policyClient = new RangerPolicyRestClient(rangerAdminUrl, AUTH_USER, AUTH_PASSWORD);
        createdPolicyIds = new ArrayList<>();

        mockS3Control = mock(S3ControlClient.class);
        deadLetterOutput = new StringWriter();
        deadLetterLogger = new DeadLetterLogger(new BufferedWriter(deadLetterOutput));

        // Stub listAccessGrants (called by applyBatch during REVOKE resolution) to return empty
        when(mockS3Control.listAccessGrants(any(Consumer.class)))
                .thenReturn(ListAccessGrantsResponse.builder().build());
    }

    // -----------------------------------------------------------------------
    // @AfterEach — cleanup created policies
    // -----------------------------------------------------------------------

    @AfterEach
    void tearDown() {
        if (createdPolicyIds != null) {
            for (int policyId : createdPolicyIds) {
                try {
                    policyClient.deletePolicy(policyId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * EMRFS policy with GetObject + PutObject for a prefix inside a registered S3 location
     * must produce a {@code CreateAccessGrant} call with:
     * <ul>
     *   <li>{@code principalArn} = the IAM ARN mapped from the Ranger user "alice"</li>
     *   <li>{@code permission} = {@code READWRITE} (GetObject=READ + PutObject=WRITE)</li>
     *   <li>{@code accountId} = the configured AWS account ID</li>
     * </ul>
     */
    @Test
    void grantCreated_whenPrefixIsRegistered_readWritePermission() throws Exception {
        // Stub the registered location: test-emrfs-bucket is a known location
        stubRegisteredLocations(REGISTERED_LOCATION, REGISTERED_LOCATION_ID);

        // Stub createAccessGrant to succeed
        when(mockS3Control.createAccessGrant(any(CreateAccessGrantRequest.class)))
                .thenReturn(CreateAccessGrantResponse.builder()
                        .accessGrantId("grant-emrfs-it-001")
                        .build());

        // Create the EMRFS policy in Ranger Admin.
        // Resource value uses "bucket/prefix" format (Ranger EMRFS service def format).
        String policyJson = "{"
                + "\"name\":\"it-emrfs-readwrite-policy\","
                + "\"service\":\"amazon-emr-emrfs\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"sthreeresource\":{"
                + "    \"values\":[\"test-emrfs-bucket/data\"],"
                + "    \"isRecursive\":false,"
                + "    \"isExcludes\":false"
                + "  }"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"" + RANGER_USER + "\"],"
                + "  \"accesses\":["
                + "    {\"type\":\"GetObject\",\"isAllowed\":true},"
                + "    {\"type\":\"PutObject\",\"isAllowed\":true}"
                + "  ]"
                + "}]"
                + "}";

        int policyId = policyClient.createPolicy(policyJson);
        createdPolicyIds.add(policyId);

        // Run the full in-process pipeline
        triggerSync();

        // Verify createAccessGrant was called at least once
        ArgumentCaptor<CreateAccessGrantRequest> captor =
                ArgumentCaptor.forClass(CreateAccessGrantRequest.class);
        verify(mockS3Control, atLeastOnce()).createAccessGrant(captor.capture());

        // Find the request targeting alice's principal ARN
        List<CreateAccessGrantRequest> requests = captor.getAllValues();
        CreateAccessGrantRequest matched = requests.stream()
                .filter(r -> r.grantee() != null
                        && PRINCIPAL_ARN.equals(r.grantee().granteeIdentifier()))
                .findFirst()
                .orElse(null);

        assertNotNull(matched,
                "Expected a CreateAccessGrant call for principalArn=" + PRINCIPAL_ARN
                        + "; captured requests: " + requests);

        // Principal must be IAM type with alice's ARN
        assertEquals(GranteeType.IAM, matched.grantee().granteeType(),
                "Grantee type should be IAM");
        assertEquals(PRINCIPAL_ARN, matched.grantee().granteeIdentifier(),
                "Principal ARN should match the mapped IAM ARN for Ranger user 'alice'");

        // GetObject (READ) + PutObject (WRITE) must aggregate to READWRITE
        assertEquals(Permission.READWRITE, matched.permission(),
                "GetObject + PutObject should produce READWRITE permission");

        // Account ID must be propagated
        assertEquals(TEST_ACCOUNT_ID, matched.accountId(),
                "Account ID in CreateAccessGrant request must match the configured account");
    }

    /**
     * EMRFS policy for an S3 prefix NOT covered by any registered Access Grants location:
     * <ul>
     *   <li>{@code CreateAccessGrant} must NOT be called.</li>
     *   <li>The dead-letter log must contain an entry referencing the unregistered bucket.</li>
     * </ul>
     */
    @Test
    void noGrantCreated_whenPrefixIsUnregistered_deadLetterWritten() throws Exception {
        // Stub the registered location: only test-emrfs-bucket is registered,
        // NOT unregistered-bucket
        stubRegisteredLocations(REGISTERED_LOCATION, REGISTERED_LOCATION_ID);

        // Create an EMRFS policy targeting an unregistered bucket
        String policyJson = "{"
                + "\"name\":\"it-emrfs-unregistered-policy\","
                + "\"service\":\"amazon-emr-emrfs\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"sthreeresource\":{"
                + "    \"values\":[\"unregistered-bucket/data\"],"
                + "    \"isRecursive\":false,"
                + "    \"isExcludes\":false"
                + "  }"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"" + RANGER_USER + "\"],"
                + "  \"accesses\":["
                + "    {\"type\":\"GetObject\",\"isAllowed\":true},"
                + "    {\"type\":\"PutObject\",\"isAllowed\":true}"
                + "  ]"
                + "}]"
                + "}";

        int policyId = policyClient.createPolicy(policyJson);
        createdPolicyIds.add(policyId);

        // Run the full in-process pipeline
        triggerSync();

        // createAccessGrant must NOT be called for an unregistered prefix
        verify(mockS3Control, never()).createAccessGrant(any(CreateAccessGrantRequest.class));

        // Dead-letter log must have an entry mentioning the unregistered bucket
        String deadLetterContent = deadLetterOutput.toString();
        assertFalse(deadLetterContent.isBlank(),
                "Dead-letter log must have an entry for the unregistered prefix");
        assertTrue(deadLetterContent.contains("unregistered-bucket"),
                "Dead-letter entry must reference the unregistered bucket. "
                        + "Content: " + deadLetterContent);
    }

    // -----------------------------------------------------------------------
    // Pipeline helper
    // -----------------------------------------------------------------------

    /**
     * Fetch current EMRFS policies from Ranger Admin and run the full in-process
     * Cedar + S3 Access Grants pipeline with the mocked S3ControlClient.
     *
     * <p>This mirrors what {@link com.amazonaws.policyconverters.sync.SyncService#executeSyncCycle()}
     * does internally for the S3AG path, without requiring a running conversion-server container.
     */
    private void triggerSync() throws Exception {
        // 1. Fetch EMRFS policies from Ranger Admin
        String endpoint = rangerAdminUrl
                + "/service/public/v2/api/service/amazon-emr-emrfs/policy";
        HttpURLConnection conn = openConnection(endpoint, "GET");
        int status = conn.getResponseCode();
        assertEquals(200, status,
                "Failed to fetch amazon-emr-emrfs policies: HTTP " + status);
        String responseBody = readStream(conn.getInputStream());
        conn.disconnect();

        List<RangerPolicy> policies = objectMapper.readValue(
                responseBody, new TypeReference<List<RangerPolicy>>() {});

        // 2. Build the Cedar conversion pipeline (EmrfsServiceAdapter → Cedar)
        AwsContext awsContext = new AwsContext(TEST_REGION, TEST_ACCOUNT_ID, TEST_ACCOUNT_ID);
        EmrfsServiceAdapter emrfsAdapter = new EmrfsServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("amazon-emr-emrfs", emrfsAdapter);

        Map<String, String> userMappings = new HashMap<>();
        userMappings.put(RANGER_USER, PRINCIPAL_ARN);
        PrincipalMappingConfig principalConfig = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());
        PrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(principalConfig, null);

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
        CedarToS3AccessGrantsConverter s3AgConverter = new CedarToS3AccessGrantsConverter();

        // 3. Convert Ranger policies → Cedar → S3AccessGrantOperations
        com.amazonaws.policyconverters.cedar.CedarPolicySet cedarPolicySet =
                rangerToCedarConverter.convert(policies);
        List<S3AccessGrantOperation> ops = s3AgConverter.convert(cedarPolicySet);

        if (ops.isEmpty()) {
            return;
        }

        // 4. Apply the operations via S3AccessGrantsClient backed by the test mock
        S3AccessGrantsConfig s3AgConfig = new S3AccessGrantsConfig(INSTANCE_ARN, TEST_ACCOUNT_ID);
        S3AccessGrantsClient s3AccessGrantsClient =
                new S3AccessGrantsClient(s3AgConfig, mockS3Control, deadLetterLogger);

        // maxRetries=0 to keep the test fast (no artificial retries on mock calls)
        s3AccessGrantsClient.applyBatch(ops, 0, 0L);
    }

    // -----------------------------------------------------------------------
    // Static HTTP helpers (mirroring DryRunPipelineIT pattern)
    // -----------------------------------------------------------------------

    private static HttpURLConnection openConnection(String endpoint, String method)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        String credentials = AUTH_USER + ":" + AUTH_PASSWORD;
        String encoded = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
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

    // -----------------------------------------------------------------------
    // Stub helpers
    // -----------------------------------------------------------------------

    /**
     * Stub {@code listAccessGrantsLocations} to return a single registered location.
     *
     * <p>Both the cache-population call (no locationScope filter) and the
     * {@code resolveLocationId} call (with a locationScope filter) are handled by
     * the same {@code any(Consumer.class)} matcher, consistent with the approach
     * in {@link com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClientTest}.
     */
    @SuppressWarnings("unchecked")
    private void stubRegisteredLocations(String locationScope, String locationId) {
        ListAccessGrantsLocationsEntry entry = ListAccessGrantsLocationsEntry.builder()
                .locationScope(locationScope)
                .accessGrantsLocationId(locationId)
                .build();
        ListAccessGrantsLocationsResponse response = ListAccessGrantsLocationsResponse.builder()
                .accessGrantsLocationsList(List.of(entry))
                .build();
        when(mockS3Control.listAccessGrantsLocations(any(Consumer.class))).thenReturn(response);
    }
}
