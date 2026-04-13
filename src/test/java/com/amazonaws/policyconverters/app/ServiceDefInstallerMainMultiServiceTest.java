package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.ranger.service.ServiceDefinitionInstallException;
import com.amazonaws.policyconverters.ranger.service.ServiceDefinitionInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ServiceDefInstallerMain multi-service iteration logic.
 * Validates Requirements 8.1, 8.2, 8.3, 8.4.
 */
@ExtendWith(MockitoExtension.class)
class ServiceDefInstallerMainMultiServiceTest {

    @Mock
    private ServiceDefinitionInstaller mockInstaller;

    private static RangerConnectionConfig rangerConfig() {
        return new RangerConnectionConfig(
                "http://ranger-admin:6080", "admin", "admin",
                null, null, null, null);
    }

    // --- Multi-service iteration tests (Req 8.1) ---

    @Test
    void installMultipleServices_allSucceed() {
        List<RangerServiceConfig> services = List.of(
                new RangerServiceConfig("lakeformation", "lf_prod", null, null),
                new RangerServiceConfig("hive", "hive_prod", null, null),
                new RangerServiceConfig("presto", "presto_prod", null, "awsdatacatalog"),
                new RangerServiceConfig("trino", "trino_prod", null, "glue_catalog")
        );

        ServiceDefInstallerMain.installMultipleServicesViaRest(
                mockInstaller, rangerConfig(), services, null);

        // Verify installer was called once per service
        verify(mockInstaller, times(4)).installViaRest(any(), any());
    }

    @Test
    void installMultipleServices_verifiesCorrectJsonLoaded() {
        List<RangerServiceConfig> services = List.of(
                new RangerServiceConfig("lakeformation", "lf_prod", null, null),
                new RangerServiceConfig("hive", "hive_prod", null, null)
        );

        ServiceDefInstallerMain.installMultipleServicesViaRest(
                mockInstaller, rangerConfig(), services, null);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockInstaller, times(2)).installViaRest(any(), jsonCaptor.capture());

        List<String> capturedJsons = jsonCaptor.getAllValues();
        // First call should contain lakeformation service def
        assertTrue(capturedJsons.get(0).contains("lakeformation"),
                "First service def should be lakeformation");
        // Second call should contain hive service def
        assertTrue(capturedJsons.get(1).contains("hive"),
                "Second service def should be hive");
    }

    // --- Partial failure tests (Req 8.3) ---

    @Test
    void installMultipleServices_partialFailure_continuesWithRemaining() {
        List<RangerServiceConfig> services = List.of(
                new RangerServiceConfig("lakeformation", "lf_prod", null, null),
                new RangerServiceConfig("hive", "hive_prod", null, null),
                new RangerServiceConfig("presto", "presto_prod", null, "awsdatacatalog")
        );

        // Fail on the second call (hive), succeed on others
        doNothing()
                .doThrow(new ServiceDefinitionInstallException("Hive install failed"))
                .doNothing()
                .when(mockInstaller).installViaRest(any(), any());

        // Should not throw - partial failure is tolerated
        ServiceDefInstallerMain.installMultipleServicesViaRest(
                mockInstaller, rangerConfig(), services, null);

        // All three should have been attempted
        verify(mockInstaller, times(3)).installViaRest(any(), any());
    }

    @Test
    void installMultipleServices_allFail_throwsRuntimeException() {
        List<RangerServiceConfig> services = List.of(
                new RangerServiceConfig("lakeformation", "lf_prod", null, null),
                new RangerServiceConfig("hive", "hive_prod", null, null)
        );

        doThrow(new ServiceDefinitionInstallException("Install failed"))
                .when(mockInstaller).installViaRest(any(), any());

        assertThrows(RuntimeException.class, () ->
                ServiceDefInstallerMain.installMultipleServicesViaRest(
                        mockInstaller, rangerConfig(), services, null));

        // Both should have been attempted
        verify(mockInstaller, times(2)).installViaRest(any(), any());
    }

    @Test
    void installMultipleServices_firstFails_secondSucceeds() {
        List<RangerServiceConfig> services = List.of(
                new RangerServiceConfig("lakeformation", "lf_prod", null, null),
                new RangerServiceConfig("hive", "hive_prod", null, null)
        );

        doThrow(new ServiceDefinitionInstallException("LF install failed"))
                .doNothing()
                .when(mockInstaller).installViaRest(any(), any());

        // Should not throw since at least one succeeded
        ServiceDefInstallerMain.installMultipleServicesViaRest(
                mockInstaller, rangerConfig(), services, null);

        verify(mockInstaller, times(2)).installViaRest(any(), any());
    }

    // --- Backward compatibility: no rangerServices → single LakeFormation (Req 8.4) ---

    @Test
    void loadServiceDefinitionJson_defaultBundled_loadsLakeFormation() throws IOException {
        String json = ServiceDefInstallerMain.loadServiceDefinitionJson(null);

        assertNotNull(json);
        assertTrue(json.contains("lakeformation"));
        assertTrue(json.contains("accessTypes"));
    }

    // --- Bundled service def loading (Req 8.4) ---

    @Test
    void loadServiceDefForConfig_noBundledDef_throwsIOException() {
        RangerServiceConfig unknownService = new RangerServiceConfig(
                "unknown_type", "instance1", null, null);

        assertThrows(IOException.class, () ->
                ServiceDefInstallerMain.loadServiceDefForConfig(unknownService, null));
    }

    @Test
    void loadServiceDefForConfig_bundledLakeFormation() throws IOException {
        RangerServiceConfig config = new RangerServiceConfig(
                "lakeformation", "lf_prod", null, null);

        String json = ServiceDefInstallerMain.loadServiceDefForConfig(config, null);

        assertNotNull(json);
        assertTrue(json.contains("lakeformation"));
    }

    @Test
    void loadServiceDefForConfig_bundledHive() throws IOException {
        RangerServiceConfig config = new RangerServiceConfig(
                "hive", "hive_prod", null, null);

        String json = ServiceDefInstallerMain.loadServiceDefForConfig(config, null);

        assertNotNull(json);
        assertTrue(json.contains("hive"));
    }

    @Test
    void loadServiceDefForConfig_bundledPresto() throws IOException {
        RangerServiceConfig config = new RangerServiceConfig(
                "presto", "presto_prod", null, "awsdatacatalog");

        String json = ServiceDefInstallerMain.loadServiceDefForConfig(config, null);

        assertNotNull(json);
        assertTrue(json.contains("presto"));
    }

    @Test
    void loadServiceDefForConfig_bundledTrino() throws IOException {
        RangerServiceConfig config = new RangerServiceConfig(
                "trino", "trino_prod", null, "glue_catalog");

        String json = ServiceDefInstallerMain.loadServiceDefForConfig(config, null);

        assertNotNull(json);
        assertTrue(json.contains("trino"));
    }

    // --- BUNDLED_SERVICE_DEFS map coverage ---

    @Test
    void bundledServiceDefs_containsAllKnownTypes() {
        assertTrue(ServiceDefInstallerMain.BUNDLED_SERVICE_DEFS.containsKey("lakeformation"));
        assertTrue(ServiceDefInstallerMain.BUNDLED_SERVICE_DEFS.containsKey("hive"));
        assertTrue(ServiceDefInstallerMain.BUNDLED_SERVICE_DEFS.containsKey("presto"));
        assertTrue(ServiceDefInstallerMain.BUNDLED_SERVICE_DEFS.containsKey("trino"));
        assertEquals(4, ServiceDefInstallerMain.BUNDLED_SERVICE_DEFS.size());
    }

    // --- Single service with custom serviceDefPath ---

    @Test
    void loadServiceDefForConfig_customPath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws IOException {
        java.nio.file.Path customDef = tempDir.resolve("custom-hive.json");
        String customJson = "{\"name\":\"custom-hive\",\"displayName\":\"Custom Hive\"}";
        java.nio.file.Files.write(customDef, customJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RangerServiceConfig config = new RangerServiceConfig(
                "hive", "hive_prod", customDef.toString(), null);

        String json = ServiceDefInstallerMain.loadServiceDefForConfig(config, null);

        assertEquals(customJson, json);
    }

    @Test
    void loadServiceDefForConfig_customPathNotFound() {
        RangerServiceConfig config = new RangerServiceConfig(
                "hive", "hive_prod", "/nonexistent/custom-hive.json", null);

        assertThrows(IOException.class, () ->
                ServiceDefInstallerMain.loadServiceDefForConfig(config, null));
    }
}
