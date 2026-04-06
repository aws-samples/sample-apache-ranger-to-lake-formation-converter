package com.amazonaws.policyconverters.ranger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDefInstallerMainTest {

    // --- CLI argument parsing tests ---

    @Test
    void parseArgs_restMode() {
        String[] args = {"--mode", "rest", "--config", "/path/to/config.yaml"};
        ServiceDefInstallerMain.CliArgs cli = ServiceDefInstallerMain.parseArgs(args);

        assertEquals("rest", cli.mode);
        assertEquals("/path/to/config.yaml", cli.configPath);
        assertNull(cli.rangerAdminHome);
        assertNull(cli.serviceDefPath);
    }

    @Test
    void parseArgs_fileMode() {
        String[] args = {"--mode", "file", "--ranger-admin-home", "/opt/ranger-admin"};
        ServiceDefInstallerMain.CliArgs cli = ServiceDefInstallerMain.parseArgs(args);

        assertEquals("file", cli.mode);
        assertEquals("/opt/ranger-admin", cli.rangerAdminHome);
        assertNull(cli.configPath);
        assertNull(cli.serviceDefPath);
    }

    @Test
    void parseArgs_withCustomServiceDef() {
        String[] args = {"--mode", "file", "--ranger-admin-home", "/opt/ranger-admin",
                "--service-def", "/custom/servicedef.json"};
        ServiceDefInstallerMain.CliArgs cli = ServiceDefInstallerMain.parseArgs(args);

        assertEquals("file", cli.mode);
        assertEquals("/opt/ranger-admin", cli.rangerAdminHome);
        assertEquals("/custom/servicedef.json", cli.serviceDefPath);
    }

    @Test
    void parseArgs_restModeWithAllOptions() {
        String[] args = {"--mode", "rest", "--config", "/path/config.yaml",
                "--service-def", "/custom/def.json"};
        ServiceDefInstallerMain.CliArgs cli = ServiceDefInstallerMain.parseArgs(args);

        assertEquals("rest", cli.mode);
        assertEquals("/path/config.yaml", cli.configPath);
        assertEquals("/custom/def.json", cli.serviceDefPath);
    }

    @Test
    void parseArgs_missingModeThrows() {
        String[] args = {"--config", "/path/to/config.yaml"};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    @Test
    void parseArgs_invalidModeThrows() {
        String[] args = {"--mode", "invalid", "--config", "/path/to/config.yaml"};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    @Test
    void parseArgs_restModeMissingConfigThrows() {
        String[] args = {"--mode", "rest"};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    @Test
    void parseArgs_fileModeMissingRangerAdminHomeThrows() {
        String[] args = {"--mode", "file"};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    @Test
    void parseArgs_unknownArgThrows() {
        String[] args = {"--mode", "rest", "--config", "/path", "--unknown", "value"};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    @Test
    void parseArgs_missingValueForFlagThrows() {
        String[] args = {"--mode"};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    @Test
    void parseArgs_emptyArgsThrows() {
        String[] args = {};
        assertThrows(IllegalArgumentException.class,
                () -> ServiceDefInstallerMain.parseArgs(args));
    }

    // --- Service definition loading tests ---

    @Test
    void loadServiceDefinitionJson_bundledDefault() throws IOException {
        String json = ServiceDefInstallerMain.loadServiceDefinitionJson(null);

        assertNotNull(json);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("lakeformation"));
        assertTrue(json.contains("accessTypes"));
    }

    @Test
    void loadServiceDefinitionJson_customPath(@TempDir Path tempDir) throws IOException {
        Path customDef = tempDir.resolve("custom-servicedef.json");
        String customJson = "{\"name\":\"custom-lf\",\"displayName\":\"Custom LF\"}";
        Files.write(customDef, customJson.getBytes(StandardCharsets.UTF_8));

        String loaded = ServiceDefInstallerMain.loadServiceDefinitionJson(customDef.toString());

        assertEquals(customJson, loaded);
    }

    @Test
    void loadServiceDefinitionJson_customPathNotFoundThrows() {
        assertThrows(IOException.class,
                () -> ServiceDefInstallerMain.loadServiceDefinitionJson("/nonexistent/path.json"));
    }

    // --- File mode integration test ---

    @Test
    void run_fileModeInstallsServiceDef(@TempDir Path tempDir) throws IOException {
        String[] args = {"--mode", "file", "--ranger-admin-home", tempDir.toString()};

        ServiceDefInstallerMain.run(args);

        Path expectedFile = tempDir
                .resolve("ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation")
                .resolve("ranger-servicedef-lakeformation.json");
        assertTrue(Files.exists(expectedFile));

        String content = new String(Files.readAllBytes(expectedFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("lakeformation"));
        assertTrue(content.contains("accessTypes"));
    }

    @Test
    void run_fileModeWithCustomServiceDef(@TempDir Path tempDir) throws IOException {
        Path customDef = tempDir.resolve("my-servicedef.json");
        String customJson = "{\"name\":\"my-lf\"}";
        Files.write(customDef, customJson.getBytes(StandardCharsets.UTF_8));

        String[] args = {"--mode", "file", "--ranger-admin-home", tempDir.toString(),
                "--service-def", customDef.toString()};

        ServiceDefInstallerMain.run(args);

        Path expectedFile = tempDir
                .resolve("ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation")
                .resolve("ranger-servicedef-lakeformation.json");
        String content = new String(Files.readAllBytes(expectedFile), StandardCharsets.UTF_8);
        assertEquals(customJson, content);
    }

    @Test
    void run_fileModeNonexistentDirThrows(@TempDir Path tempDir) {
        String[] args = {"--mode", "file", "--ranger-admin-home",
                tempDir.resolve("nonexistent").toString()};

        assertThrows(IllegalStateException.class,
                () -> ServiceDefInstallerMain.run(args));
    }

    // --- REST mode validation test ---

    @Test
    void run_restModeNonexistentConfigThrows(@TempDir Path tempDir) {
        String[] args = {"--mode", "rest", "--config",
                tempDir.resolve("nonexistent.yaml").toString()};

        assertThrows(IOException.class,
                () -> ServiceDefInstallerMain.run(args));
    }
}
