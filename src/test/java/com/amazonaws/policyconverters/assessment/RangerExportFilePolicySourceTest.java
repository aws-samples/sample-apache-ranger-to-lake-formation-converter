package com.amazonaws.policyconverters.assessment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangerExportFilePolicySourceTest {

    @TempDir
    Path tempDir;

    @Test
    void load_wellFormedExport_assessesKnownServicesAndSkipsUnknown() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":"hive_prod","serviceType":"hive","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":2,"name":"p2","service":"lf_prod","serviceType":"lakeformation","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":3,"name":"p3","service":"yarn_prod","serviceType":"yarn","isEnabled":true,
                   "resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();

        assertEquals(3, batches.size());
        ServicePolicyBatch hive = batch(batches, "hive_prod");
        assertFalse(hive.isSkipped());
        assertEquals(1, hive.getPolicies().size());

        ServicePolicyBatch lf = batch(batches, "lf_prod");
        assertFalse(lf.isSkipped());
        assertEquals(1, lf.getPolicies().size());

        ServicePolicyBatch yarn = batch(batches, "yarn_prod");
        assertTrue(yarn.isSkipped());
        assertEquals(1, yarn.getRawPolicyCount());
        assertNotNull(yarn.getSkipReason());
    }

    @Test
    void load_disabledPoliciesFilteredFromPoliciesButCountedInRaw() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":"lf_prod","serviceType":"lakeformation","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":2,"name":"p2","service":"lf_prod","serviceType":"lakeformation","isEnabled":false,
                   "resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();

        assertEquals(1, batches.size());
        ServicePolicyBatch batch = batches.get(0);
        assertFalse(batch.isSkipped());
        assertEquals(1, batch.getPolicies().size(), "disabled policy must be excluded");
        assertEquals(2, batch.getRawPolicyCount(), "rawPolicyCount must include disabled policy");
    }

    @Test
    void load_emptyPoliciesArray_returnsEmptyBatches() throws IOException {
        Path file = writeExport(tempDir, "{\"policies\":[]}");
        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertTrue(batches.isEmpty());
    }

    @Test
    void load_missingPoliciesKey_returnsEmptyBatches() throws IOException {
        Path file = writeExport(tempDir, "{}");
        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertTrue(batches.isEmpty());
    }

    @Test
    void load_policyWithNullService_isSkippedIndividually() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":null,"serviceType":"hive","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":2,"name":"p2","service":"hive_prod","serviceType":"hive","isEnabled":true,
                   "resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertEquals(1, batches.size());
        assertEquals("hive_prod", batches.get(0).getServiceName());
        assertEquals(1, batches.get(0).getPolicies().size());
    }

    @Test
    void load_emrfsServiceType_isAssessed() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":"emrfs_prod","serviceType":"amazon-emr-emrfs",
                   "isEnabled":true,"resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertEquals(1, batches.size());
        assertFalse(batches.get(0).isSkipped(), "amazon-emr-emrfs must be assessed, not skipped");
    }

    @Test
    void load_invalidJson_throwsRuntimeException(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "not valid json", StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class,
                () -> new RangerExportFilePolicySource(file).load());
    }

    @Test
    void load_sourceLabel_returnsFilenameOnly() throws IOException {
        Path file = writeExport(tempDir, "{\"policies\":[]}");
        String label = new RangerExportFilePolicySource(file).sourceLabel();
        assertEquals("file:" + file.getFileName().toString(), label);
    }

    // ---- helpers ----

    private Path writeExport(Path dir, String json) throws IOException {
        Path file = dir.resolve("export.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private ServicePolicyBatch batch(List<ServicePolicyBatch> batches, String serviceName) {
        return batches.stream()
                .filter(b -> serviceName.equals(b.getServiceName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No batch for service: " + serviceName));
    }
}
