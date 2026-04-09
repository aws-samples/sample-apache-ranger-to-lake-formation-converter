package com.amazonaws.policyconverters.ranger;

import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RangerPlugin.
 * Validates: Requirements 4.1
 */
class RangerPluginTest {

    private RangerPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new RangerPlugin();
    }

    @Test
    void constructorSetsServiceTypeAndAppId() {
        assertEquals("lakeformation", plugin.getServiceType());
        assertEquals("lakeformation", plugin.getAppId());
    }

    @Test
    void serviceTypeConstantMatchesExpectedValue() {
        assertEquals("lakeformation", RangerPlugin.SERVICE_TYPE);
    }

    @Test
    void appIdConstantMatchesExpectedValue() {
        assertEquals("lakeformation", RangerPlugin.APP_ID);
    }

    @Test
    void getLatestPoliciesReturnsNullBeforeAnyUpdate() {
        assertNull(plugin.getLatestPolicies());
    }

    @Test
    void getLatestPolicyListReturnsEmptyBeforeAnyUpdate() {
        assertTrue(plugin.getLatestPolicyList().isEmpty());
    }

    @Test
    void setPoliciesStoresLatestPolicies() {
        ServicePolicies sp = createServicePolicies(3L, 2);

        plugin.setPolicies(sp);

        assertSame(sp, plugin.getLatestPolicies());
    }

    @Test
    void setPoliciesUpdatesLatestPolicyList() {
        ServicePolicies sp = createServicePolicies(1L, 3);

        plugin.setPolicies(sp);

        assertEquals(3, plugin.getLatestPolicyList().size());
    }

    @Test
    void setPoliciesIgnoresNullPolicies() {
        // First set valid policies
        ServicePolicies sp = createServicePolicies(1L, 2);
        plugin.setPolicies(sp);

        // Then set null — should be ignored
        plugin.setPolicies(null);

        // Original policies should still be there
        assertSame(sp, plugin.getLatestPolicies());
    }

    @Test
    void setPoliciesNotifiesRegisteredListener() {
        AtomicReference<ServicePolicies> received = new AtomicReference<>();
        plugin.setPolicyUpdateListener(received::set);

        ServicePolicies sp = createServicePolicies(5L, 1);
        plugin.setPolicies(sp);

        assertSame(sp, received.get());
    }

    @Test
    void setPoliciesDoesNotFailWithoutListener() {
        // No listener registered — should not throw
        ServicePolicies sp = createServicePolicies(1L, 1);
        assertDoesNotThrow(() -> plugin.setPolicies(sp));
    }

    @Test
    void setPoliciesHandlesListenerException() {
        plugin.setPolicyUpdateListener(policies -> {
            throw new RuntimeException("Listener error");
        });

        ServicePolicies sp = createServicePolicies(1L, 1);

        // Should not propagate the exception
        assertDoesNotThrow(() -> plugin.setPolicies(sp));

        // Policies should still be stored despite listener failure
        assertSame(sp, plugin.getLatestPolicies());
    }

    @Test
    void setPoliciesUpdatesOnSubsequentCalls() {
        ServicePolicies sp1 = createServicePolicies(1L, 1);
        ServicePolicies sp2 = createServicePolicies(2L, 3);

        plugin.setPolicies(sp1);
        assertSame(sp1, plugin.getLatestPolicies());

        plugin.setPolicies(sp2);
        assertSame(sp2, plugin.getLatestPolicies());
    }

    @Test
    void setPoliciesHandlesEmptyPolicyList() {
        ServicePolicies sp = createServicePolicies(1L, 0);

        plugin.setPolicies(sp);

        assertSame(sp, plugin.getLatestPolicies());
        assertTrue(plugin.getLatestPolicyList().isEmpty());
    }

    @Test
    void setPoliciesHandlesNullPolicyListInServicePolicies() {
        ServicePolicies sp = new ServicePolicies();
        sp.setPolicyVersion(1L);
        sp.setPolicies(null);

        plugin.setPolicies(sp);

        assertSame(sp, plugin.getLatestPolicies());
        assertTrue(plugin.getLatestPolicyList().isEmpty());
    }

    @Test
    void listenerReceivesEachUpdate() {
        List<ServicePolicies> updates = new ArrayList<>();
        plugin.setPolicyUpdateListener(updates::add);

        ServicePolicies sp1 = createServicePolicies(1L, 1);
        ServicePolicies sp2 = createServicePolicies(2L, 2);

        plugin.setPolicies(sp1);
        plugin.setPolicies(sp2);

        assertEquals(2, updates.size());
        assertSame(sp1, updates.get(0));
        assertSame(sp2, updates.get(1));
    }

    /**
     * Helper to create a ServicePolicies with a given version and number of policies.
     */
    private ServicePolicies createServicePolicies(long version, int policyCount) {
        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(version);

        List<RangerPolicy> policies = new ArrayList<>();
        for (int i = 0; i < policyCount; i++) {
            RangerPolicy policy = new RangerPolicy();
            policy.setId((long) (i + 1));
            policy.setName("policy-" + (i + 1));
            policy.setService("lakeformation");
            policies.add(policy);
        }
        sp.setPolicies(policies);

        return sp;
    }
}
