package com.amazonaws.policyconverters.ranger.it;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test that installs the Lake Formation service definition into a live
 * Ranger Admin instance. Requires a running Ranger Admin (see integration-test/scripts/start-ranger.sh).
 *
 * Tests are ordered: create runs first, then update (which handles the 409 conflict case),
 * then error handling.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ServiceDefInstallIT {

    private static final String DEFAULT_RANGER_URL = "http://localhost:6080";
    private static final String SERVICEDEF_ENDPOINT = "/service/public/v2/api/servicedef";
    private static final String SERVICEDEF_RESOURCE = "/ranger-servicedef-lakeformation.json";
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASSWORD = "rangerR0cks!";

    private final String rangerAdminUrl;

    public ServiceDefInstallIT() {
        this.rangerAdminUrl = System.getProperty("ranger.admin.url", DEFAULT_RANGER_URL);
    }

    @Test
    @Order(1)
    void testCreateServiceDef() {
        String serviceDefJson = loadServiceDefJson();
        String endpoint = rangerAdminUrl + SERVICEDEF_ENDPOINT;

        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, serviceDefJson);

            int statusCode = conn.getResponseCode();

            if (statusCode == 409) {
                // Already exists — not a failure, update test will handle it
                return;
            }

            String responseBody = readResponseBody(conn, statusCode);
            assertEquals(200, statusCode,
                    "Expected HTTP 200 from POST " + endpoint + ", got " + statusCode + ": " + responseBody);
            assertTrue(responseBody.contains("lakeformation"),
                    "Response body should contain 'lakeformation', got: " + responseBody);
        } catch (IOException e) {
            fail("Failed to connect to " + endpoint + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Test
    @Order(2)
    void testUpdateServiceDef() {
        String serviceDefJson = loadServiceDefJson();

        // First, find the existing servicedef ID
        int existingId = findExistingServiceDefId();
        if (existingId < 0) {
            // No existing servicedef — create succeeded in testCreateServiceDef, nothing to update
            return;
        }

        String endpoint = rangerAdminUrl + SERVICEDEF_ENDPOINT + "/" + existingId;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "PUT");
            writeBody(conn, serviceDefJson);

            int statusCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn, statusCode);
            assertEquals(200, statusCode,
                    "Expected HTTP 200 from PUT " + endpoint + ", got " + statusCode + ": " + responseBody);
        } catch (IOException e) {
            fail("Failed to connect to " + endpoint + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Test
    @Order(3)
    void testErrorHandling() {
        // Send an invalid JSON body to trigger an error response
        String invalidJson = "{\"invalid\": true}";
        String endpoint = rangerAdminUrl + SERVICEDEF_ENDPOINT;

        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, invalidJson);

            int statusCode = conn.getResponseCode();
            // We expect a 4xx or 5xx error
            assertTrue(statusCode >= 400,
                    "Expected an error status code (4xx/5xx) for bad request, got " + statusCode);

            String errorBody = readErrorBody(conn);
            String errorMessage = buildErrorMessage(statusCode, errorBody);

            // Verify the error message includes the HTTP status code
            assertTrue(errorMessage.contains(String.valueOf(statusCode)),
                    "Error message should contain HTTP status code " + statusCode + ", got: " + errorMessage);

            // Verify the error message includes the response body
            assertTrue(errorMessage.contains(errorBody),
                    "Error message should contain response body, got: " + errorMessage);
        } catch (IOException e) {
            fail("Failed to connect to " + endpoint + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Helper methods ----

    /**
     * Loads the service definition JSON from the classpath.
     */
    private String loadServiceDefJson() {
        try (InputStream is = getClass().getResourceAsStream(SERVICEDEF_RESOURCE)) {
            if (is == null) {
                throw new AssertionError("Service definition resource not found on classpath: " + SERVICEDEF_RESOURCE);
            }
            return readStream(is);
        } catch (IOException e) {
            throw new AssertionError("Failed to read service definition resource: " + e.getMessage(), e);
        }
    }

    /**
     * Finds the ID of an existing 'lakeformation' service definition by querying the API.
     * Returns -1 if not found.
     */
    private int findExistingServiceDefId() {
        String endpoint = rangerAdminUrl + SERVICEDEF_ENDPOINT + "/name/lakeformation";
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "GET");
            int statusCode = conn.getResponseCode();
            if (statusCode == 200) {
                String body = readResponseBody(conn, statusCode);
                // Parse the id from the JSON response (simple extraction)
                return extractId(body);
            }
            return -1;
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Extracts the "id" field from a JSON response string.
     * Uses simple string parsing to avoid adding a JSON library dependency.
     */
    private int extractId(String json) {
        // Look for "id": followed by a number
        String marker = "\"id\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return -1;
        }
        int start = idx + marker.length();
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    /**
     * Opens an HTTP connection with Basic auth and JSON content type.
     */
    private HttpURLConnection openConnection(String endpoint, String method) throws IOException {
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

    /**
     * Writes a JSON body to the connection's output stream.
     */
    private void writeBody(HttpURLConnection conn, String body) throws IOException {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /**
     * Reads the response body from a successful response.
     */
    private String readResponseBody(HttpURLConnection conn, int statusCode) {
        try {
            InputStream is = (statusCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                return "";
            }
            return readStream(is);
        } catch (IOException e) {
            return "(unable to read response body)";
        }
    }

    /**
     * Reads the error response body from a failed response.
     */
    private String readErrorBody(HttpURLConnection conn) {
        try {
            InputStream errorStream = conn.getErrorStream();
            if (errorStream == null) {
                return "(no error body)";
            }
            return readStream(errorStream);
        } catch (IOException e) {
            return "(unable to read error body)";
        }
    }

    /**
     * Builds a descriptive error message including the HTTP status code and response body.
     */
    static String buildErrorMessage(int statusCode, String responseBody) {
        return "Service definition request failed: HTTP " + statusCode + " - " + responseBody;
    }

    /**
     * Reads an InputStream fully into a String.
     */
    private String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }
}
