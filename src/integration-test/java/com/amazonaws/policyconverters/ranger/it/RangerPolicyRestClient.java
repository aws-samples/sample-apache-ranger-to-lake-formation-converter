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
 * Test helper that manages Ranger policies and tags via the Ranger Admin REST API.
 * Supports creating, updating, and deleting policies and tag resources with Basic authentication.
 * Consistent with the HTTP patterns used in {@link ServiceDefInstallIT}.
 */
public class RangerPolicyRestClient {

    private static final String POLICY_ENDPOINT = "/service/public/v2/api/policy";
    private static final String TAG_DEF_ENDPOINT = "/service/tags/tagdef";
    private static final String TAG_RESOURCE_ENDPOINT = "/service/tags/resource";
    private static final String TAG_ENDPOINT = "/service/tags/tag";
    private static final String TAG_MAP_ENDPOINT = "/service/tags/tagresourcemap";

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

    // ---- Tag management methods ----

    /**
     * Create a Ranger tag definition and return the created tag definition ID.
     *
     * @param tagName the name of the tag (e.g., "PII", "SENSITIVE")
     * @return the tag definition ID
     */
    public int createTagDef(String tagName) {
        String body = "{\"name\":\"" + tagName + "\",\"attributeDefs\":[]}";
        String endpoint = rangerAdminUrl + TAG_DEF_ENDPOINT;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, body);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Create tag def failed: HTTP " + statusCode + " - " + responseBody);
            }
            return extractId(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create tag def at " + endpoint, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Delete a Ranger tag definition by ID.
     */
    public void deleteTagDef(int tagDefId) {
        String endpoint = rangerAdminUrl + TAG_DEF_ENDPOINT + "/" + tagDefId;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "DELETE");
            conn.getResponseCode();
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete tag def " + tagDefId, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Create a Ranger service resource (mapping a Glue database/table to the tag service)
     * and return the created resource ID.
     *
     * @param tagServiceName the Ranger tag service instance name (e.g., "cl_tag")
     * @param resourceJson   the resource elements JSON (e.g., a database or table spec)
     * @return the resource ID
     */
    public int createTagServiceResource(String tagServiceName, String resourceJson) {
        String body = "{\"serviceName\":\"" + tagServiceName + "\",\"resourceElements\":"
                + resourceJson + "}";
        String endpoint = rangerAdminUrl + TAG_RESOURCE_ENDPOINT;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, body);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Create tag resource failed: HTTP " + statusCode + " - " + responseBody);
            }
            return extractId(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create tag service resource at " + endpoint, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Delete a Ranger service resource by ID.
     */
    public void deleteTagServiceResource(int resourceId) {
        String endpoint = rangerAdminUrl + TAG_RESOURCE_ENDPOINT + "/" + resourceId;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "DELETE");
            conn.getResponseCode();
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete tag resource " + resourceId, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Create a tag instance and return the created tag instance ID.
     *
     * @param tagType the tag type name (must match a tag definition name)
     * @return the tag instance ID
     */
    public int createTagInstance(String tagType) {
        String body = "{\"type\":\"" + tagType + "\",\"attributes\":{}}";
        String endpoint = rangerAdminUrl + TAG_ENDPOINT;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, body);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Create tag instance failed: HTTP " + statusCode + " - " + responseBody);
            }
            return extractId(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create tag instance at " + endpoint, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Delete a tag instance by ID.
     */
    public void deleteTagInstance(int tagInstanceId) {
        String endpoint = rangerAdminUrl + TAG_ENDPOINT + "/" + tagInstanceId;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "DELETE");
            conn.getResponseCode();
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete tag instance " + tagInstanceId, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Associate a tag instance with a service resource (create a resource-tag mapping).
     *
     * @param resourceId     the service resource ID
     * @param tagInstanceIds list of tag instance IDs to attach
     */
    public void createResourceTagMapping(int resourceId, java.util.List<Integer> tagInstanceIds) {
        StringBuilder tagIds = new StringBuilder("[");
        for (int i = 0; i < tagInstanceIds.size(); i++) {
            if (i > 0) tagIds.append(",");
            tagIds.append(tagInstanceIds.get(i));
        }
        tagIds.append("]");
        String body = "{\"resourceId\":" + resourceId + ",\"tagIds\":" + tagIds + "}";
        String endpoint = rangerAdminUrl + TAG_MAP_ENDPOINT;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(endpoint, "POST");
            writeBody(conn, body);
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Create tag-resource mapping failed: HTTP " + statusCode
                        + " - " + responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create tag-resource mapping at " + endpoint, e);
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
