package com.amazonaws.policyconverters.ranger.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Test helper that manages Ranger policies via the Ranger Admin REST API.
 * Supports creating, updating, and deleting policies with Basic authentication.
 * Consistent with the HTTP patterns used in {@link ServiceDefInstallIT}.
 */
public class RangerPolicyRestClient {

    private static final String POLICY_ENDPOINT = "/service/public/v2/api/policy";

    private final String rangerAdminUrl;
    private final String authHeader;

    public RangerPolicyRestClient(String rangerAdminUrl, String username, String password) {
        this.rangerAdminUrl = rangerAdminUrl != null ? rangerAdminUrl : "http://localhost:6080";
        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a Ranger policy via POST and returns the created policy ID.
     *
     * @param policyJson the policy JSON body
     * @return the ID of the created policy
     */
    public int createPolicy(String policyJson) {
        String endpoint = rangerAdminUrl + POLICY_ENDPOINT;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, policyJson);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Create policy failed: HTTP " + statusCode + " - " + responseBody);
            }
            return extractId(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create policy at " + endpoint, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Updates an existing Ranger policy via PUT.
     *
     * @param policyId   the ID of the policy to update
     * @param policyJson the updated policy JSON body
     */
    public void updatePolicy(int policyId, String policyJson) {
        String endpoint = rangerAdminUrl + POLICY_ENDPOINT + "/" + policyId;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "PUT");
            writeBody(conn, policyJson);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Update policy failed: HTTP " + statusCode + " - " + responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to update policy " + policyId + " at " + endpoint, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Deletes a Ranger policy via DELETE.
     *
     * @param policyId the ID of the policy to delete
     */
    public void deletePolicy(int policyId) {
        String endpoint = rangerAdminUrl + POLICY_ENDPOINT + "/" + policyId;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "DELETE");
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Delete policy failed: HTTP " + statusCode + " - " + responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete policy " + policyId + " at " + endpoint, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String endpoint, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", authHeader);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        if ("POST".equals(method) || "PUT".equals(method)) {
            conn.setDoOutput(true);
        }
        return conn;
    }

    private void writeBody(HttpURLConnection conn, String body) throws IOException {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private String readResponse(HttpURLConnection conn, int statusCode) {
        try {
            InputStream is = (statusCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "";
            return readStream(is);
        } catch (IOException e) {
            return "(unable to read response)";
        }
    }

    private String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    private int extractId(String json) {
        String marker = "\"id\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            throw new RuntimeException("Could not extract policy ID from response: " + json);
        }
        int start = idx + marker.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            throw new RuntimeException("Could not parse policy ID from response: " + json);
        }
        return Integer.parseInt(json.substring(start, end));
    }
}
