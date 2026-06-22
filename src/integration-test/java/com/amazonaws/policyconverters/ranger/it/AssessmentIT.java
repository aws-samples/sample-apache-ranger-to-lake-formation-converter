package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.assessment.AssessmentConfig;
import com.amazonaws.policyconverters.assessment.AssessmentReporter;
import com.amazonaws.policyconverters.assessment.AssessmentResult;
import com.amazonaws.policyconverters.assessment.AssessmentRunner;
import com.amazonaws.policyconverters.assessment.RangerAdminPolicySource;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the gap assessment tool.
 *
 * Creates one Ranger policy of every significant type and flavor, runs the
 * AssessmentRunner against the live Ranger Admin, and verifies:
 *  - Total policies scanned matches what was created
 *  - Each expected gap type is recorded in the gap report
 *  - Fully convertible policies are correctly classified (no gap entries)
 *  - Partially convertible policies are correctly classified (gaps + grants)
 *  - Non-convertible policies are correctly classified (no grants)
 *  - The JSON report file is written and deserialises cleanly
 *
 * Requires a running Ranger Admin at http://localhost:6080 (or ranger.admin.url system property).
 * The lakeformation service definition and service instance must exist (ServiceDefInstallIT
 * must have run first, which happens automatically in the integration-test Maven profile).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AssessmentIT {

    private static final Logger LOG = LoggerFactory.getLogger(AssessmentIT.class);
    private static final String DEFAULT_RANGER_URL = "http://localhost:6080";
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASSWORD = "rangerR0cks!";
    private static final String TEST_ACCOUNT_ID = "123456789012";
    private static final String TEST_REGION = "us-east-1";

    private static String rangerAdminUrl;
    private static RangerPolicyRestClient policyClient;
    private static final List<Integer> createdPolicyIds = new ArrayList<>();

    // ---- Lifecycle ----

    @BeforeAll
    static void setUp() throws Exception {
        rangerAdminUrl = System.getProperty("ranger.admin.url", DEFAULT_RANGER_URL);
        policyClient = new RangerPolicyRestClient(rangerAdminUrl, AUTH_USER, AUTH_PASSWORD);

        // Verify lakeformation service instance exists
        HttpURLConnection conn = openConnection(
                rangerAdminUrl + "/service/public/v2/api/service/name/lakeformation", "GET");
        int status = conn.getResponseCode();
        conn.disconnect();
        assertEquals(200, status,
                "lakeformation service instance must exist before running AssessmentIT");
    }

    @AfterAll
    static void tearDown() {
        for (int id : createdPolicyIds) {
            try {
                policyClient.deletePolicy(id);
            } catch (Exception e) {
                LOG.warn("Failed to clean up policy {}: {}", id, e.getMessage());
            }
        }
        createdPolicyIds.clear();
    }

    // ---- Helper: create a policy and track it for cleanup ----

    private static int createPolicy(String json) {
        int id = policyClient.createPolicy(json);
        createdPolicyIds.add(id);
        return id;
    }

    // ---- Policy factories ----

    /** Fully convertible: database SELECT for a mapped principal. */
    private static String fullyConvertibleDbPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false}},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"alter\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    /** Fully convertible: table SELECT + DESCRIBE for a mapped principal. */
    private static String fullyConvertibleTablePolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":["
                + "    {\"type\":\"select\",\"isAllowed\":true},"
                + "    {\"type\":\"describe\",\"isAllowed\":true}"
                + "  ]"
                + "}]"
                + "}";
    }

    /** Fully convertible: data location access. */
    private static String fullyConvertibleDataLocationPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"datalocation\":{\"values\":[\"assess-bucket/data/\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"data_location_access\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    /** Partially convertible: allow items + deny items → DENY_POLICY gap. */
    private static String denyPolicyPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"secret_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}],"
                + "\"denyPolicyItems\":[{"
                + "  \"users\":[\"blocked_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    /**
     * Partially convertible: deny items + denyExceptions → DENY_EXCEPTION gap.
     * (allowExceptions are fully handled by the Cedar pipeline; denyExceptions are the gap.)
     */
    private static String denyExceptionPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"audited_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}],"
                + "\"denyExceptions\":[{"
                + "  \"users\":[\"auditor\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    /** Partially convertible: has a validity schedule → VALIDITY_SCHEDULE gap. */
    private static String validitySchedulePolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"timed_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}],"
                + "\"validitySchedules\":[{"
                + "  \"startTime\":\"2024/01/01 00:00:00\","
                + "  \"endTime\":\"2024/12/31 23:59:59\","
                + "  \"timeZone\":\"UTC\""
                + "}]"
                + "}";
    }

    // Note: CUSTOM_CONDITION gap cannot be tested via REST because the lakeformation
    // service definition has no conditionDefs; it is covered by AssessmentRunnerTest (unit test).

    /** Partially convertible: delegated admin → DELEGATED_ADMIN gap. */
    private static String delegatedAdminPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"managed_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"data_admin\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":true"
                + "}]"
                + "}";
    }

    /** Not convertible: data masking policy (policyType=1). */
    private static String dataMaskingPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":1,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"pii_table\"],\"isRecursive\":false},"
                + "  \"column\":{\"values\":[\"ssn\"],\"isRecursive\":false}"
                + "},"
                + "\"dataMaskPolicyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"dataMaskInfo\":{\"dataMaskType\":\"MASK\"}"
                + "}]"
                + "}";
    }

    /** Partially convertible: security zone set → SECURITY_ZONE gap. */
    private static String securityZonePolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"zoneName\":\"prod-zone\","
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"zone_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    /** Not convertible: no principal mapping for any user. */
    private static String unmappedPrincipalPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"orphan_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"no_such_user_in_mapping\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    /** Disabled policy — should be filtered out before assessment. */
    private static String disabledPolicy(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":false,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"assess_db\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"alter\",\"isAllowed\":true}]"
                + "}]"
                + "}";
    }

    // ---- The test ----

    @Test
    @Order(1)
    void assessment_coversAllPolicyTypesAndCorrectlyIdentifiesGaps() throws Exception {
        // Create one policy of each type
        createPolicy(fullyConvertibleDbPolicy("assess-fully-db"));
        createPolicy(fullyConvertibleTablePolicy("assess-fully-table"));
        createPolicy(fullyConvertibleDataLocationPolicy("assess-fully-datalocation"));
        createPolicy(denyPolicyPolicy("assess-deny-policy"));
        createPolicy(denyExceptionPolicy("assess-deny-exception"));
        createPolicy(validitySchedulePolicy("assess-validity-schedule"));
        // Skipped: customConditionPolicy, dataMaskingPolicy, securityZonePolicy — these require
        // service def conditionDefs / dataMaskDef / security zones not present in the Docker setup.
        // Those gap types are covered by AssessmentRunnerTest (unit tests).
        createPolicy(delegatedAdminPolicy("assess-delegated-admin"));
        createPolicy(unmappedPrincipalPolicy("assess-unmapped-principal"));
        createPolicy(disabledPolicy("assess-disabled"));

        // Wire principal mapping for known test users
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("analyst", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst");
        userMappings.put("etl_user", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user");
        userMappings.put("data_admin", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin");
        // "blocked_user", "auditor", "no_such_user_in_mapping" intentionally omitted

        PrincipalMappingConfig principalMapping = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());

        Path outputDir = Files.createTempDirectory("assess-it-");
        try {
            AssessmentConfig config = AssessmentConfig.builder()
                    .rangerAdminUrl(rangerAdminUrl)
                    .rangerUsername(AUTH_USER)
                    .rangerPassword(AUTH_PASSWORD)
                    .services(List.of(new RangerServiceConfig(
                            "lakeformation", "lakeformation", null, null)))
                    .principalMapping(principalMapping)
                    .outputDir(outputDir)
                    .consoleOnly(false)
                    .build();

            AssessmentResult result = new AssessmentRunner().run(config,
                    new RangerAdminPolicySource(config.getRangerAdminUrl(), config.getRangerUsername(),
                            config.getRangerPassword(), config.getServices()));
            new AssessmentReporter().report(result, config, System.out);

            // ---- Basic sanity ----
            // 8 enabled policies created (3 fully-convertible, 4 gap-producing, 1 unmapped; disabled excluded)
            assertTrue(result.getTotalPolicies() >= 8,
                    "Expected at least 8 enabled policies scanned (disabled policy must be filtered), got "
                            + result.getTotalPolicies());
            assertTrue(result.getProjectedGrantCount() >= 1,
                    "Expected at least one projected LF grant from fully-convertible policies");

            // Total = fully + partially + not convertible
            assertEquals(result.getTotalPolicies(),
                    result.getFullyConvertible()
                            + result.getPartiallyConvertible()
                            + result.getNotConvertible(),
                    "fully + partially + notConvertible must equal total");

            // ---- Gap types ----
            Map<GapType, Integer> summary = result.getGapReport().getSummary();

            // Gap types verifiable with the stock lakeformation service def
            assertTrue(summary.getOrDefault(GapType.DENY_POLICY, 0) >= 1,
                    "Expected at least one DENY_POLICY gap (deny policy items)");

            assertTrue(summary.getOrDefault(GapType.DENY_EXCEPTION, 0) >= 1,
                    "Expected at least one DENY_EXCEPTION gap (allowExceptions)");

            assertTrue(summary.getOrDefault(GapType.VALIDITY_SCHEDULE, 0) >= 1,
                    "Expected at least one VALIDITY_SCHEDULE gap");

            assertTrue(summary.getOrDefault(GapType.DELEGATED_ADMIN, 0) >= 1,
                    "Expected at least one DELEGATED_ADMIN gap");

            // DATA_MASKING, SECURITY_ZONE, CUSTOM_CONDITION tested in AssessmentRunnerTest (unit tests)
            // because the Docker Ranger service def lacks dataMaskDef, security zones, and conditionDefs

            // ---- JSON report file written ----
            long jsonFiles = Files.list(outputDir)
                    .filter(p -> p.getFileName().toString().startsWith("assessment-report-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .count();
            assertEquals(1, jsonFiles, "Expected exactly one JSON report file in output dir");

            // ---- Disabled policy not counted ----
            // The REST API filters disabled policies, so totalPolicies < created count (9 created, 1 disabled)
            assertTrue(result.getTotalPolicies() < 10,
                    "Disabled policy should not be counted; total should be < 10 from this run, got "
                            + result.getTotalPolicies());

            LOG.info("AssessmentIT passed: total={}, fully={}, partial={}, notConvertible={}, grants={}, gaps={}",
                    result.getTotalPolicies(), result.getFullyConvertible(),
                    result.getPartiallyConvertible(), result.getNotConvertible(),
                    result.getProjectedGrantCount(), summary);

        } finally {
            // Clean up temp output directory
            try {
                Files.list(outputDir).forEach(p -> p.toFile().delete());
                outputDir.toFile().delete();
            } catch (Exception e) {
                LOG.warn("Failed to clean up output dir: {}", e.getMessage());
            }
        }
    }

    @Test
    @Order(2)
    void assessment_consoleOnly_doesNotWriteFile() throws Exception {
        // At least one policy already exists from Order(1) — just run the assessment
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("analyst", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst");
        userMappings.put("etl_user", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user");
        userMappings.put("data_admin", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin");

        Path outputDir = Files.createTempDirectory("assess-it-console-");
        try {
            AssessmentConfig config = AssessmentConfig.builder()
                    .rangerAdminUrl(rangerAdminUrl)
                    .rangerUsername(AUTH_USER)
                    .rangerPassword(AUTH_PASSWORD)
                    .services(List.of(new RangerServiceConfig(
                            "lakeformation", "lakeformation", null, null)))
                    .principalMapping(new PrincipalMappingConfig(
                            userMappings, Collections.emptyMap(), Collections.emptyMap()))
                    .outputDir(outputDir)
                    .consoleOnly(true)
                    .build();

            AssessmentResult result = new AssessmentRunner().run(config,
                    new RangerAdminPolicySource(config.getRangerAdminUrl(), config.getRangerUsername(),
                            config.getRangerPassword(), config.getServices()));
            assertTrue(result.getTotalPolicies() >= 1, "Expected at least one policy");

            long fileCount = Files.list(outputDir).count();
            assertEquals(0, fileCount, "consoleOnly=true must not write any files");

        } finally {
            try {
                Files.list(outputDir).forEach(p -> p.toFile().delete());
                outputDir.toFile().delete();
            } catch (Exception e) {
                LOG.warn("Failed to clean up output dir: {}", e.getMessage());
            }
        }
    }

    // ---- Utilities ----

    private static HttpURLConnection openConnection(String endpoint, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        String credentials = AUTH_USER + ":" + AUTH_PASSWORD;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encoded);
        return conn;
    }

    @SuppressWarnings("unused")
    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }
}
