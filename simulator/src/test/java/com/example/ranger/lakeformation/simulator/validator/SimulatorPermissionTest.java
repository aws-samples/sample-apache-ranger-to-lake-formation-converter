package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorPermissionTest {

    private static final String PRINCIPAL = "arn:aws:iam::123456789012:role/MyRole";
    private static final String RESOURCE_TYPE = "TABLE";
    private static final String RESOURCE_ID = "arn:aws:glue:us-east-1:123456789012:table/mydb/mytable";
    private static final String PERMISSION = "SELECT";

    @Test
    void recordEqualityHoldsWhenAllFieldsMatch() {
        SimulatorPermission a = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, false);
        SimulatorPermission b = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, false);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void grantableFlagDifferentiatesRecords() {
        SimulatorPermission withGrant = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, true);
        SimulatorPermission withoutGrant = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, false);

        assertNotEquals(withGrant, withoutGrant);
    }

    @Test
    void recordsDeduplicateCorrectlyInHashSet() {
        SimulatorPermission p1 = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, false);
        SimulatorPermission p2 = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, false);
        SimulatorPermission p3 = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, true);

        Set<SimulatorPermission> set = new HashSet<>();
        set.add(p1);
        set.add(p2);  // duplicate of p1
        set.add(p3);  // different (grantable=true)

        assertEquals(2, set.size(), "HashSet should deduplicate identical records");
        assertTrue(set.contains(p1));
        assertTrue(set.contains(p3));
    }

    @Test
    void fieldsAreAccessibleViaAccessors() {
        SimulatorPermission p = new SimulatorPermission(PRINCIPAL, RESOURCE_TYPE, RESOURCE_ID, PERMISSION, true);

        assertEquals(PRINCIPAL, p.principalArn());
        assertEquals(RESOURCE_TYPE, p.resourceType());
        assertEquals(RESOURCE_ID, p.resourceId());
        assertEquals(PERMISSION, p.permission());
        assertTrue(p.grantable());
    }
}
