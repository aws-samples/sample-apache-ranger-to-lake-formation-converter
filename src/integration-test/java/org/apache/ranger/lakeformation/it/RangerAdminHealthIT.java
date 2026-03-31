package org.apache.ranger.lakeformation.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test that verifies Ranger Admin is reachable and responsive.
 * Requires a running Ranger Admin instance (see integration-test/scripts/start-ranger.sh).
 */
public class RangerAdminHealthIT {

    private static final String DEFAULT_RANGER_URL = "http://localhost:6080";

    private final String rangerAdminUrl;

    public RangerAdminHealthIT() {
        this.rangerAdminUrl = System.getProperty("ranger.admin.url", DEFAULT_RANGER_URL);
    }

    @Test
    void testLoginPageReachable() {
        String endpoint = rangerAdminUrl + "/login.jsp";
        int statusCode = httpGet(endpoint);
        assertEquals(200, statusCode, "Expected HTTP 200 from " + endpoint);
    }

    @Test
    void testServiceDefApiReachable() {
        String endpoint = rangerAdminUrl + "/service/public/v2/api/servicedef";
        int statusCode = httpGet(endpoint);
        assertEquals(200, statusCode, "Expected HTTP 200 from " + endpoint);
    }

    /**
     * Sends an HTTP GET request and returns the status code.
     * On connection failure, throws an AssertionError with the attempted URL and original exception message.
     */
    static int httpGet(String endpoint) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            return conn.getResponseCode();
        } catch (IOException e) {
            throw new AssertionError(
                    "Failed to connect to " + endpoint + ": " + e.getMessage(), e);
        }
    }
}
