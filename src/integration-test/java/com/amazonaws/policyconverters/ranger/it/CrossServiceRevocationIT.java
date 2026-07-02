package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.HiveServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.amazonaws.policyconverters.sync.SyncService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for CROSS-SERVICE forward-sync revocation correctness.
 *
 * <p>Scenario (mirrors the simulator sequence that first looked like a leak): a lakeformation-service
 * policy grants (analyst, DB, DROP+CREATE_TABLE); it is deleted (revoked); then a HIVE-service policy
 * re-grants the identical Lake Formation resource+permissions; that hive policy is then deleted. The
 * final deletion must revoke the tuple — no grant may outlive its policy across services.
 *
 * <p>Wires a MULTI-service pipeline (lakeformation + hive adapters) and, each sync cycle, fetches
 * BOTH service instances and merges them — mirroring the production REST multi-service path
 * ({@code ConversionServerMain.mergeServicePolicies}). This test PASSES: cross-service revocation is
 * correct. It confirms the simulator's apparent orphans were an oracle blind spot (the hive service
 * was omitted from the expected-permissions computation, since fixed in
 * {@code SimulatorMain.buildAllServiceNames}), not a sync defect. Retained as a regression guard.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CrossServiceRevocationIT {

    private static final String RANGER_URL = System.getProperty("ranger.admin.url", "http://localhost:6080");
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASSWORD = "rangerR0cks!";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String REGION = "us-east-1";
    private static final List<String> SERVICES = List.of("lakeformation", "hive");

    private Path outputDir;
    private ObjectMapper mapper;
    private RangerPolicyRestClient policyClient;
    private SyncService syncService;
    private final List<Integer> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        outputDir = Files.createTempDirectory("xsvc-leak-it-");
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        policyClient = new RangerPolicyRestClient(RANGER_URL, AUTH_USER, AUTH_PASSWORD);

        DryRunLakeFormationClient dryRunClient = new DryRunLakeFormationClient(outputDir, mapper);
        AwsContext awsContext = new AwsContext(REGION, ACCOUNT_ID, ACCOUNT_ID);

        // Multi-service adapter registry: both lakeformation and hive.
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("lakeformation", new RangerServiceAdapter(awsContext));
        adapterRegistry.put("hive", new HiveServiceAdapter(awsContext));

        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("analyst", "arn:aws:iam::" + ACCOUNT_ID + ":role/analyst");
        PrincipalMappingConfig principalConfig = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());
        PrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(principalConfig, null);

        CatalogResolver passthrough = new CatalogResolver(null) {
            @Override public List<String> expandDatabases(String p) { return List.of(p); }
            @Override public List<String> expandTables(String db, String p) { return List.of(p); }
            @Override public List<String> expandColumns(String db, String t, String p) { return List.of(p); }
        };

        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schema = new CedarSchemaProvider();
        RangerToCedarConverter r2c = new RangerToCedarConverter(
                adapterRegistry, principalMapper, passthrough, gapReporter, schema);
        CedarToLFConverter c2lf = new CedarToLFConverter(schema, gapReporter, null);

        DeadLetterLogger deadLetter = new DeadLetterLogger(
                Files.newBufferedWriter(outputDir.resolve("dead-letter.jsonl")));

        // Single-plugin constructor with a null plugin: the pipeline is driven purely by
        // onPoliciesUpdated() (we feed merged multi-service policies ourselves), matching the
        // production REST multi-service executor.
        syncService = new SyncService(
                (com.amazonaws.policyconverters.ranger.RangerPlugin) null,
                r2c, c2lf, dryRunClient, gapReporter, deadLetter);
        syncService.start(new SyncConfig(null, null, null, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        if (syncService != null && syncService.isRunning()) syncService.stop();
        for (int id : created) {
            try { policyClient.deletePolicy(id); } catch (Exception ignored) {}
        }
        if (outputDir != null) {
            File[] files = outputDir.toFile().listFiles();
            if (files != null) for (File f : files) f.delete();
            outputDir.toFile().delete();
        }
    }

    @Test
    void lakeformationThenHive_regrantThenDelete_revokesFully() throws Exception {
        String analystArn = "arn:aws:iam::" + ACCOUNT_ID + ":role/analyst";

        // Cycle 1: lakeformation-service policy grants (analyst, xsvc_db DB, CREATE_TABLE+DROP).
        int lfId = createAndTrack(lfDbPolicy("xsvc-lf", "analyst", "xsvc_db", "create_table", "drop"));
        triggerSync();

        // Cycle 2: delete the lakeformation policy — tuple revoked.
        deleteAndUntrack(lfId);
        triggerSync();

        // Cycle 3: HIVE-service policy re-grants the identical LF resource+permissions
        // (hive "create"->CREATE_TABLE, "drop"->DROP on the same database).
        int hiveId = createAndTrack(hiveDbPolicy("xsvc-hive", "analyst", "xsvc_db", "create", "drop"));
        triggerSync();

        // Cycle 4: delete the hive policy — tuple MUST be revoked.
        deleteAndUntrack(hiveId);
        triggerSync();

        Set<String> live = livePermsFor(analystArn, "xsvc_db");
        assertTrue(live.isEmpty(),
                "Cross-service revocation must be complete: after a lakeformation grant was revoked, "
                        + "re-granted by a hive policy, and that hive policy deleted, analyst must "
                        + "retain NO live LF permissions on xsvc_db. Leaked: " + live);
    }

    // ---- helpers ----

    /** Net live permissions (GRANTs minus REVOKEs across all cycles) for a principal+db, as "TYPE:PERM". */
    private Set<String> livePermsFor(String principalArn, String db) throws Exception {
        Set<String> live = new HashSet<>();
        File[] files = outputDir.toFile().listFiles(
                (d, n) -> n.startsWith("dry-run-") && n.endsWith(".json"));
        if (files == null) return live;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            DryRunOutput out = mapper.readValue(f, DryRunOutput.class);
            for (LFPermissionOperation op : out.getOperations()) {
                if (op.getResource() == null) continue;
                if (!principalArn.equals(op.getPrincipalArn())) continue;
                if (!db.equals(op.getResource().getDatabaseName())) continue;
                for (LFPermission perm : op.getPermissions()) {
                    String key = op.getResource().getTableName() + ":" + perm;
                    if (op.getOperationType() == OperationType.GRANT) live.add(key);
                    else live.remove(key);
                }
            }
        }
        return live;
    }

    private int createAndTrack(String json) {
        int id = policyClient.createPolicy(json);
        created.add(id);
        return id;
    }

    private void deleteAndUntrack(int id) {
        policyClient.deletePolicy(id);
        created.remove(Integer.valueOf(id));
    }

    /** Fetch ALL configured service instances and merge, mirroring production mergeServicePolicies. */
    private void triggerSync() throws Exception {
        List<RangerPolicy> merged = new ArrayList<>();
        for (String svc : SERVICES) {
            HttpURLConnection conn = open(
                    RANGER_URL + "/service/public/v2/api/service/" + svc + "/policy", "GET");
            int status = conn.getResponseCode();
            String body = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            conn.disconnect();
            if (status != 200) continue;
            merged.addAll(mapper.readValue(body, new TypeReference<List<RangerPolicy>>() {}));
        }
        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("multi-service");
        sp.setPolicies(merged);
        sp.setPolicyVersion(System.currentTimeMillis());
        syncService.onPoliciesUpdated(sp);
    }

    private String lfDbPolicy(String name, String user, String db, String... accesses) {
        return dbPolicy(name, "lakeformation", user, db, accesses);
    }

    private String hiveDbPolicy(String name, String user, String db, String... accesses) {
        return dbPolicy(name, "hive", user, db, accesses);
    }

    private String dbPolicy(String name, String service, String user, String db, String... accesses) {
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < accesses.length; i++) {
            if (i > 0) acc.append(",");
            acc.append("{\"type\":\"").append(accesses[i]).append("\",\"isAllowed\":true}");
        }
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"service\":\"" + service + "\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"" + db + "\"],\"isRecursive\":false}},"
                + "\"policyItems\":[{\"users\":[\"" + user + "\"],\"accesses\":[" + acc + "]}]"
                + "}";
    }

    private static HttpURLConnection open(String endpoint, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        String creds = AUTH_USER + ":" + AUTH_PASSWORD;
        conn.setRequestProperty("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8)));
        return conn;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
