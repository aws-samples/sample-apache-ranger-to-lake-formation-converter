package com.example.ranger.lakeformation.simulator.status;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SyncServiceStatusClientTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void registerHandler(String path, int statusCode, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void fetchStatus_parsesAllFields() throws IOException, InterruptedException {
        String json = "{\"lastCompletedCycle\":5,\"lastCompletedWildcardRefreshCycle\":3,\"state\":\"running\"}";
        registerHandler("/status", 200, json);

        SyncServiceStatusClient client = new SyncServiceStatusClient("localhost", port);
        SyncServiceStatusClient.StatusResponse response = client.fetchStatus();

        assertEquals(5L, response.lastCompletedCycle());
        assertEquals(3L, response.lastCompletedWildcardRefreshCycle());
        assertEquals("running", response.state());
    }

    @Test
    void fetchStatus_throwsOnNon200() {
        registerHandler("/status", 503, "Service Unavailable");

        SyncServiceStatusClient client = new SyncServiceStatusClient("localhost", port);
        IOException ex = assertThrows(IOException.class, client::fetchStatus);
        assertTrue(ex.getMessage().contains("503"), "Exception message should contain status code 503");
    }

    @Test
    void fetchStatus_ignoresExtraJsonFields() throws IOException, InterruptedException {
        String json = "{\"lastCompletedCycle\":10,\"lastCompletedWildcardRefreshCycle\":7,\"state\":\"idle\",\"unknownField\":\"value\"}";
        registerHandler("/status", 200, json);

        SyncServiceStatusClient client = new SyncServiceStatusClient("localhost", port);
        SyncServiceStatusClient.StatusResponse response = client.fetchStatus();

        assertEquals(10L, response.lastCompletedCycle());
        assertEquals(7L, response.lastCompletedWildcardRefreshCycle());
        assertEquals("idle", response.state());
    }
}
