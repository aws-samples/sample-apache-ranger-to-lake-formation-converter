package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LFPermissionOperationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructAndGetters() {
        LFResource resource = new LFResource("cat", "db", "tbl", null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT, LFPermission.DESCRIBE);
        LFPermissionOperation op = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT,
                "policy-42", "arn:aws:iam::123:role/Analyst",
                resource, perms, true);

        assertEquals(LFPermissionOperation.OperationType.GRANT, op.getOperationType());
        assertEquals("policy-42", op.getSourcePolicyId());
        assertEquals("arn:aws:iam::123:role/Analyst", op.getPrincipalArn());
        assertEquals(resource, op.getResource());
        assertEquals(perms, op.getPermissions());
        assertTrue(op.isGrantable());
    }

    @Test
    void permissionsAreImmutable() {
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        LFPermissionOperation op = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT,
                "p1", "arn", new LFResource("c", "d", null, null, null),
                perms, false);
        assertThrows(UnsupportedOperationException.class, () -> op.getPermissions().add(LFPermission.DROP));
    }

    @Test
    void equalsAndHashCode() {
        LFResource res = new LFResource("cat", "db", null, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        LFPermissionOperation a = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1", "arn", res, perms, false);
        LFPermissionOperation b = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1", "arn", res, perms, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferentType() {
        LFResource res = new LFResource("cat", "db", null, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        LFPermissionOperation grant = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1", "arn", res, perms, false);
        LFPermissionOperation revoke = new LFPermissionOperation(
                LFPermissionOperation.OperationType.REVOKE, "p1", "arn", res, perms, false);
        assertNotEquals(grant, revoke);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        LFResource res = new LFResource("123456789012", "analytics", "events", null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT, LFPermission.INSERT);
        LFPermissionOperation original = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT,
                "policy-99", "arn:aws:iam::123:user/admin",
                res, perms, true);

        String json = mapper.writeValueAsString(original);
        LFPermissionOperation deserialized = mapper.readValue(json, LFPermissionOperation.class);
        assertEquals(original, deserialized);
    }

    @Test
    void operationTypeFromValue() {
        assertEquals(LFPermissionOperation.OperationType.GRANT,
                LFPermissionOperation.OperationType.fromValue("grant"));
        assertEquals(LFPermissionOperation.OperationType.REVOKE,
                LFPermissionOperation.OperationType.fromValue("REVOKE"));
        assertThrows(IllegalArgumentException.class,
                () -> LFPermissionOperation.OperationType.fromValue("UNKNOWN"));
    }
}
