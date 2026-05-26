package com.amazonaws.policyconverters.app;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class StatusEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(StatusEndpoint.class);

    private final int port;
    private final AtomicLong lastCompletedCycle;
    private final AtomicLong lastCompletedWildcardRefreshCycle;
    private HttpServer server;

    public StatusEndpoint(int port,
                          AtomicLong lastCompletedCycle,
                          AtomicLong lastCompletedWildcardRefreshCycle) {
        this.port = port;
        this.lastCompletedCycle = lastCompletedCycle;
        this.lastCompletedWildcardRefreshCycle = lastCompletedWildcardRefreshCycle;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/status", exchange -> {
            String body = String.format(
                    "{\"lastCompletedCycle\":%d,\"lastCompletedWildcardRefreshCycle\":%d,\"state\":\"running\"}",
                    lastCompletedCycle.get(),
                    lastCompletedWildcardRefreshCycle.get());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        server.start();
        LOG.info("StatusEndpoint started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
