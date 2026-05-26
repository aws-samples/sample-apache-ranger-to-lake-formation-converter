package com.amazonaws.policyconverters.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StatusEndpointTest {

    private StatusEndpoint endpoint;
    private AtomicLong cycleCounter;
    private AtomicLong wildcardRefreshCounter;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        cycleCounter = new AtomicLong(0);
        wildcardRefreshCounter = new AtomicLong(0);
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        endpoint = new StatusEndpoint(port, cycleCounter, wildcardRefreshCounter);
        endpoint.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        endpoint.stop();
    }

    @Test
    void getStatusReturnsJsonWithCounters() throws Exception {
        cycleCounter.set(5);
        wildcardRefreshCounter.set(3);

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"lastCompletedCycle\":5"));
        assertTrue(response.body().contains("\"lastCompletedWildcardRefreshCycle\":3"));
        assertTrue(response.body().contains("\"state\":\"running\""));
    }
}
