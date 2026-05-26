package com.example.ranger.lakeformation.simulator.status;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class SyncServiceStatusClient {
    private static final Logger LOG = LoggerFactory.getLogger(SyncServiceStatusClient.class);

    private final String statusUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public SyncServiceStatusClient(String host, int port) {
        this.statusUrl = "http://" + host + ":" + port + "/status";
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    /**
     * Fetch current status from the sync service.
     * @throws IOException if the HTTP call fails or returns non-200
     */
    public StatusResponse fetchStatus() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(statusUrl))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("fetchStatus failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), StatusResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusResponse(
            @JsonProperty("lastCompletedCycle") long lastCompletedCycle,
            @JsonProperty("lastCompletedWildcardRefreshCycle") long lastCompletedWildcardRefreshCycle,
            @JsonProperty("state") String state
    ) {}
}
