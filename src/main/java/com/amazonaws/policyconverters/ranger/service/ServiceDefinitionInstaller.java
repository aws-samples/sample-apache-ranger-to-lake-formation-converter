package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Registers the custom Lake Formation service definition with Ranger Admin.
 * Supports two installation modes:
 * <ul>
 *   <li>REST-based: POST to /service/plugins/definitions</li>
 *   <li>File-based: copy to ranger-admin plugin directory</li>
 * </ul>
 */
public class ServiceDefinitionInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceDefinitionInstaller.class);
    private static final String REST_ENDPOINT = "/service/plugins/definitions";
    private static final String FILE_INSTALL_SUBPATH =
            "ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation";
    private static final String SERVICE_DEF_FILENAME = "ranger-servicedef-lakeformation.json";

    private final HttpConnectionFactory connectionFactory;

    /**
     * Functional interface for creating HTTP connections, enabling testability.
     */
    public interface HttpConnectionFactory {
        HttpURLConnection create(URL url) throws IOException;
    }

    public ServiceDefinitionInstaller() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    public ServiceDefinitionInstaller(HttpConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Register the service definition with Ranger Admin via REST API
     * POST to /service/plugins/definitions.
     *
     * @param config                Ranger connection configuration
     * @param serviceDefinitionJson the service definition JSON content
     * @throws ServiceDefinitionInstallException on failure
     */
    public void installViaRest(RangerConnectionConfig config, String serviceDefinitionJson) {
        if (config == null) {
            throw new IllegalArgumentException("RangerConnectionConfig must not be null");
        }
        if (serviceDefinitionJson == null || serviceDefinitionJson.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceDefinitionJson must not be null or empty");
        }

        String baseUrl = config.getRangerAdminUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Ranger Admin URL must not be null or empty");
        }
        // Strip trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String endpoint = baseUrl + REST_ENDPOINT;
        LOG.info("Installing service definition via REST: POST {}", endpoint);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = connectionFactory.create(url);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            // Set basic auth if credentials are available
            if (config.getUsername() != null && config.getPassword() != null) {
                String credentials = config.getUsername() + ":" + config.getPassword();
                String encoded = Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }

            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream();
                 OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                writer.write(serviceDefinitionJson);
                writer.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                LOG.info("Service definition installed successfully via REST (HTTP {})", responseCode);
            } else {
                String errorBody = readErrorStream(conn);
                String msg = String.format(
                        "Failed to install service definition via REST: HTTP %d - %s",
                        responseCode, errorBody);
                LOG.error(msg);
                throw new ServiceDefinitionInstallException(msg);
            }
        } catch (ServiceDefinitionInstallException e) {
            throw e;
        } catch (IOException e) {
            String msg = "Failed to connect to Ranger Admin at " + endpoint;
            LOG.error(msg, e);
            throw new ServiceDefinitionInstallException(msg, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Install the service definition by copying the JSON file to the
     * Ranger Admin plugin directory.
     *
     * @param rangerAdminHome       path to the ranger-admin home directory
     * @param serviceDefinitionJson the service definition JSON content
     * @throws ServiceDefinitionInstallException on failure
     */
    public void installViaFile(Path rangerAdminHome, String serviceDefinitionJson) {
        if (rangerAdminHome == null) {
            throw new IllegalArgumentException("rangerAdminHome must not be null");
        }
        if (serviceDefinitionJson == null || serviceDefinitionJson.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceDefinitionJson must not be null or empty");
        }

        Path targetDir = rangerAdminHome.resolve(FILE_INSTALL_SUBPATH);
        Path targetFile = targetDir.resolve(SERVICE_DEF_FILENAME);

        LOG.info("Installing service definition via file: {}", targetFile);

        try {
            Files.createDirectories(targetDir);
            Files.write(targetFile, serviceDefinitionJson.getBytes(StandardCharsets.UTF_8));
            LOG.info("Service definition installed successfully at {}", targetFile);
        } catch (IOException e) {
            String msg = "Failed to write service definition to " + targetFile;
            LOG.error(msg, e);
            throw new ServiceDefinitionInstallException(msg, e);
        }
    }

    private String readErrorStream(HttpURLConnection conn) {
        try {
            InputStream errorStream = conn.getErrorStream();
            if (errorStream == null) {
                return "(no error body)";
            }
            byte[] bytes = new byte[4096];
            int len = errorStream.read(bytes);
            if (len <= 0) {
                return "(empty error body)";
            }
            return new String(bytes, 0, len, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unable to read error body)";
        }
    }
}
