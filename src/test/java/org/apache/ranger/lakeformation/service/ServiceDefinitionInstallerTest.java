package org.apache.ranger.lakeformation.service;

import org.apache.ranger.lakeformation.model.RangerConnectionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceDefinitionInstallerTest {

    private static final String SAMPLE_JSON = "{\"name\":\"lakeformation\",\"displayName\":\"AWS Lake Formation\"}";

    private HttpURLConnection mockConnection;
    private ServiceDefinitionInstaller installer;
    private ByteArrayOutputStream capturedOutput;

    @BeforeEach
    void setUp() throws IOException {
        mockConnection = mock(HttpURLConnection.class);
        capturedOutput = new ByteArrayOutputStream();
        when(mockConnection.getOutputStream()).thenReturn(capturedOutput);

        ServiceDefinitionInstaller.HttpConnectionFactory factory = url -> mockConnection;
        installer = new ServiceDefinitionInstaller(factory);
    }

    // --- REST installation tests ---

    @Test
    void installViaRest_success() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(200);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        installer.installViaRest(config, SAMPLE_JSON);

        verify(mockConnection).setRequestMethod("POST");
        verify(mockConnection).setRequestProperty("Content-Type", "application/json");
        verify(mockConnection).setDoOutput(true);
        assertEquals(SAMPLE_JSON, capturedOutput.toString("UTF-8"));
    }

    @Test
    void installViaRest_setsBasicAuth() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(200);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "secret",
                null, null, null, null);

        installer.installViaRest(config, SAMPLE_JSON);

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                "admin:secret".getBytes(StandardCharsets.UTF_8));
        verify(mockConnection).setRequestProperty("Authorization", expectedAuth);
    }

    @Test
    void installViaRest_noAuthWhenCredentialsMissing() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(200);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", null, null,
                null, null, null, null);

        installer.installViaRest(config, SAMPLE_JSON);

        verify(mockConnection, never()).setRequestProperty(eq("Authorization"), anyString());
    }

    @Test
    void installViaRest_httpErrorThrowsException() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(403);
        when(mockConnection.getErrorStream()).thenReturn(
                new ByteArrayInputStream("Forbidden".getBytes(StandardCharsets.UTF_8)));

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        ServiceDefinitionInstallException ex = assertThrows(
                ServiceDefinitionInstallException.class,
                () -> installer.installViaRest(config, SAMPLE_JSON));

        assertTrue(ex.getMessage().contains("403"));
        assertTrue(ex.getMessage().contains("Forbidden"));
    }

    @Test
    void installViaRest_connectionFailureThrowsException() throws IOException {
        ServiceDefinitionInstaller.HttpConnectionFactory failingFactory =
                url -> { throw new IOException("Connection refused"); };
        ServiceDefinitionInstaller failInstaller = new ServiceDefinitionInstaller(failingFactory);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        ServiceDefinitionInstallException ex = assertThrows(
                ServiceDefinitionInstallException.class,
                () -> failInstaller.installViaRest(config, SAMPLE_JSON));

        assertTrue(ex.getMessage().contains("Failed to connect"));
    }

    @Test
    void installViaRest_trailingSlashStripped() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(200);

        // Use a factory that captures the URL
        final URL[] capturedUrl = new URL[1];
        ServiceDefinitionInstaller.HttpConnectionFactory capturingFactory = url -> {
            capturedUrl[0] = url;
            return mockConnection;
        };
        ServiceDefinitionInstaller inst = new ServiceDefinitionInstaller(capturingFactory);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080/", "admin", "password",
                null, null, null, null);

        inst.installViaRest(config, SAMPLE_JSON);

        assertEquals("http://ranger:6080/service/plugins/definitions",
                capturedUrl[0].toString());
    }

    @Test
    void installViaRest_nullConfigThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> installer.installViaRest(null, SAMPLE_JSON));
    }

    @Test
    void installViaRest_nullJsonThrows() {
        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> installer.installViaRest(config, null));
    }

    @Test
    void installViaRest_emptyJsonThrows() {
        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> installer.installViaRest(config, "  "));
    }

    // --- File-based installation tests ---

    @Test
    void installViaFile_createsDirectoryAndWritesFile(@TempDir Path tempDir) throws IOException {
        installer.installViaFile(tempDir, SAMPLE_JSON);

        Path expectedFile = tempDir
                .resolve("ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation")
                .resolve("ranger-servicedef-lakeformation.json");

        assertTrue(Files.exists(expectedFile));
        String content = new String(Files.readAllBytes(expectedFile), StandardCharsets.UTF_8);
        assertEquals(SAMPLE_JSON, content);
    }

    @Test
    void installViaFile_overwritesExistingFile(@TempDir Path tempDir) throws IOException {
        // First install
        installer.installViaFile(tempDir, "{\"old\":true}");

        // Second install overwrites
        installer.installViaFile(tempDir, SAMPLE_JSON);

        Path expectedFile = tempDir
                .resolve("ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation")
                .resolve("ranger-servicedef-lakeformation.json");

        String content = new String(Files.readAllBytes(expectedFile), StandardCharsets.UTF_8);
        assertEquals(SAMPLE_JSON, content);
    }

    @Test
    void installViaFile_nullPathThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> installer.installViaFile(null, SAMPLE_JSON));
    }

    @Test
    void installViaFile_nullJsonThrows(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class,
                () -> installer.installViaFile(tempDir, null));
    }

    @Test
    void installViaFile_emptyJsonThrows(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class,
                () -> installer.installViaFile(tempDir, ""));
    }

    @Test
    void installViaRest_201CreatedIsSuccess() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(201);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        // Should not throw
        installer.installViaRest(config, SAMPLE_JSON);

        verify(mockConnection).setRequestMethod("POST");
    }

    @Test
    void installViaRest_500ServerErrorThrowsException() throws IOException {
        when(mockConnection.getResponseCode()).thenReturn(500);
        when(mockConnection.getErrorStream()).thenReturn(null);

        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "password",
                null, null, null, null);

        ServiceDefinitionInstallException ex = assertThrows(
                ServiceDefinitionInstallException.class,
                () -> installer.installViaRest(config, SAMPLE_JSON));

        assertTrue(ex.getMessage().contains("500"));
    }
}
