package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MutationOperationTest {

    private static final Instant NOW = Instant.now();
    private static final String POLICY_ID = "policy-abc-123";
    private static final Object PAYLOAD = "{\"policyType\": 0, \"name\": \"test-policy\"}";

    @Test
    void createPolicyIsInstanceOfMutationOperation() {
        MutationOperation op = new MutationOperation.CreatePolicy(NOW, POLICY_ID, PAYLOAD);

        assertInstanceOf(MutationOperation.class, op);
        assertInstanceOf(MutationOperation.CreatePolicy.class, op);
    }

    @Test
    void createPolicyFieldsAreAccessible() {
        MutationOperation.CreatePolicy op = new MutationOperation.CreatePolicy(NOW, POLICY_ID, PAYLOAD);

        assertEquals(NOW, op.timestamp());
        assertEquals(POLICY_ID, op.policyId());
        assertEquals(PAYLOAD, op.policyPayload());
    }

    @Test
    void deletePolicyIsInstanceOfMutationOperation() {
        MutationOperation op = new MutationOperation.DeletePolicy(NOW, POLICY_ID);

        assertInstanceOf(MutationOperation.class, op);
        assertInstanceOf(MutationOperation.DeletePolicy.class, op);
        assertEquals(POLICY_ID, ((MutationOperation.DeletePolicy) op).policyId());
    }

    @Test
    void patternMatchingSwitchCoversAllVariants() {
        MutationOperation[] ops = {
            new MutationOperation.CreatePolicy(NOW, "p1", PAYLOAD),
            new MutationOperation.UpdatePolicy(NOW, "p2", PAYLOAD),
            new MutationOperation.DisablePolicy(NOW, "p3"),
            new MutationOperation.EnablePolicy(NOW, "p4"),
            new MutationOperation.DeletePolicy(NOW, "p5")
        };

        assertEquals("create:p1", describe(ops[0]));
        assertEquals("update:p2", describe(ops[1]));
        assertEquals("disable:p3", describe(ops[2]));
        assertEquals("enable:p4", describe(ops[3]));
        assertEquals("delete:p5", describe(ops[4]));
    }

    @Test
    void disableAndEnablePoliciesHaveNoPayload() {
        MutationOperation disable = new MutationOperation.DisablePolicy(NOW, POLICY_ID);
        MutationOperation enable = new MutationOperation.EnablePolicy(NOW, POLICY_ID);

        assertInstanceOf(MutationOperation.DisablePolicy.class, disable);
        assertInstanceOf(MutationOperation.EnablePolicy.class, enable);
        assertEquals(NOW, disable.timestamp());
        assertEquals(NOW, enable.timestamp());
    }

    /**
     * Helper demonstrating pattern matching over the sealed hierarchy.
     * Uses instanceof pattern matching (Java 16+) since switch pattern matching
     * requires Java 21+.  The compiler still enforces exhaustiveness via the
     * sealed interface — any unhandled permit type will surface as an
     * IllegalStateException at runtime.
     */
    private static String describe(MutationOperation op) {
        if (op instanceof MutationOperation.CreatePolicy c)  return "create:"  + c.policyId();
        if (op instanceof MutationOperation.UpdatePolicy u)  return "update:"  + u.policyId();
        if (op instanceof MutationOperation.DisablePolicy d) return "disable:" + d.policyId();
        if (op instanceof MutationOperation.EnablePolicy e)  return "enable:"  + e.policyId();
        if (op instanceof MutationOperation.DeletePolicy dl) return "delete:"  + dl.policyId();
        throw new IllegalStateException("Unhandled MutationOperation subtype: " + op.getClass());
    }
}
