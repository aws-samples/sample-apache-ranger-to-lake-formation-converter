package com.example.ranger.lakeformation.simulator.driver;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RangerPolicyClientTest {

    private static final String POLICY_PATH = "/service/public/v2/api/policy";

    private HttpServer server;
    private int port;
    private RangerPolicyClient client;

    @BeforeEach
    void setUp() throws IOException {
        port = findEphemeralPort();
        server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.start();
        client = new RangerPolicyClient("http://localhost:" + port, "admin", "password");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int findEphemeralPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // -------------------------------------------------------------------------
    // createPolicy
    // -------------------------------------------------------------------------

    @Test
    void createPolicySendsPostAndReturnsId() throws Exception {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();

        server.createContext(POLICY_PATH, exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            String responseBody = "{\"id\": \"42\", \"name\": \"test-policy\"}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        Map<String, Object> policy = Map.of("name", "test-policy", "service", "hive");
        String id = client.createPolicy(policy);

        assertEquals("POST", capturedMethod.get(), "createPolicy must use POST");
        assertEquals(POLICY_PATH, capturedPath.get(), "createPolicy must POST to the correct path");
        assertEquals("42", id, "createPolicy must return the 'id' field from the response");
    }

    @Test
    void createPolicyThrowsIoExceptionOnServerError() {
        server.createContext(POLICY_PATH, exchange -> {
            String responseBody = "{\"error\": \"internal server error\"}";
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        Map<String, Object> policy = Map.of("name", "bad-policy");
        assertThrows(IOException.class, () -> client.createPolicy(policy),
                "createPolicy must throw IOException on HTTP 500");
    }

    // -------------------------------------------------------------------------
    // deletePolicy
    // -------------------------------------------------------------------------

    @Test
    void deletePolicySendsDeleteAndSucceedsOn204() throws Exception {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();

        server.createContext(POLICY_PATH + "/99", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        assertDoesNotThrow(() -> client.deletePolicy("99"),
                "deletePolicy must not throw on 204");
        assertEquals("DELETE", capturedMethod.get(), "deletePolicy must use DELETE");
        assertEquals(POLICY_PATH + "/99", capturedPath.get(), "deletePolicy must target the correct path");
    }

    @Test
    void deletePolicySucceedsOn200() throws Exception {
        server.createContext(POLICY_PATH + "/77", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.getResponseBody().close();
        });

        assertDoesNotThrow(() -> client.deletePolicy("77"),
                "deletePolicy must not throw on 200");
    }

    @Test
    void deletePolicyThrowsIoExceptionOnError() {
        server.createContext(POLICY_PATH + "/bad", exchange -> {
            String body = "not found";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        assertThrows(IOException.class, () -> client.deletePolicy("bad"),
                "deletePolicy must throw IOException on HTTP 404");
    }

    // -------------------------------------------------------------------------
    // updatePolicy
    // -------------------------------------------------------------------------

    @Test
    void updatePolicySendsPutToCorrectPath() throws Exception {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();

        server.createContext(POLICY_PATH + "/55", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            byte[] responseBody = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });

        Map<String, Object> policy = Map.of("name", "updated-policy", "isEnabled", true);
        assertDoesNotThrow(() -> client.updatePolicy("55", policy),
                "updatePolicy must not throw on 200");

        assertEquals("PUT", capturedMethod.get(), "updatePolicy must use PUT");
        assertEquals(POLICY_PATH + "/55", capturedPath.get(), "updatePolicy must PUT to the correct path");
    }

    @Test
    void updatePolicyThrowsIoExceptionOnError() {
        server.createContext(POLICY_PATH + "/fail", exchange -> {
            String body = "server error";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        Map<String, Object> policy = Map.of("name", "bad");
        assertThrows(IOException.class, () -> client.updatePolicy("fail", policy),
                "updatePolicy must throw IOException on HTTP 500");
    }
}
