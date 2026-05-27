package com.example.ranger.lakeformation.simulator.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper around the Ranger Admin REST API for policy CRUD.
 * Base URL: {rangerAdminUrl}/service/public/v2/api/policy
 */
public class RangerPolicyClient {
    private static final Logger LOG = LoggerFactory.getLogger(RangerPolicyClient.class);
    private static final String POLICY_PATH = "/service/public/v2/api/policy";

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public RangerPolicyClient(String rangerAdminUrl, String username, String password) {
        this.baseUrl = rangerAdminUrl;
        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    /**
     * Create a policy. Returns the created policy's numeric ID as assigned by Ranger.
     * @param policyJson the full policy JSON as a Map (will be serialized)
     * @return the created policy ID string
     * @throws IOException if the HTTP call fails or returns non-200
     */
    public String createPolicy(Map<String, Object> policyJson) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(policyJson);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + POLICY_PATH))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("createPolicy failed: HTTP " + response.statusCode() + " — " + response.body());
        }
        JsonNode json = mapper.readTree(response.body());
        return json.path("id").asText();
    }

    /**
     * Fetch a single policy by its numeric ID.
     */
    public JsonNode getPolicy(String policyId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + POLICY_PATH + "/" + policyId))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("getPolicy failed: HTTP " + response.statusCode() + " — " + response.body());
        }
        return mapper.readTree(response.body());
    }

    /**
     * Update an existing policy by ID.
     */
    public void updatePolicy(String policyId, Map<String, Object> policyJson) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(policyJson);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + POLICY_PATH + "/" + policyId))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("updatePolicy failed: HTTP " + response.statusCode() + " — " + response.body());
        }
    }

    /**
     * Delete a policy by ID.
     */
    public void deletePolicy(String policyId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + POLICY_PATH + "/" + policyId))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IOException("deletePolicy failed: HTTP " + response.statusCode() + " — " + response.body());
        }
    }

    /**
     * Fetch all policies for a given service name.
     * Returns raw JSON as a JsonNode (array).
     */
    public JsonNode listPolicies(String serviceName) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + POLICY_PATH + "?serviceName=" + serviceName))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("listPolicies failed: HTTP " + response.statusCode() + " — " + response.body());
        }
        return mapper.readTree(response.body());
    }

    /**
     * Enable or disable a policy by setting its "isEnabled" field.
     */
    public void setPolicyEnabled(String policyId, boolean enabled, Map<String, Object> currentPolicyJson)
            throws IOException, InterruptedException {
        Map<String, Object> updated = new HashMap<>(currentPolicyJson);
        updated.put("isEnabled", enabled);
        updatePolicy(policyId, updated);
    }
}
