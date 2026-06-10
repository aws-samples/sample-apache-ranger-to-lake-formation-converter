package com.amazonaws.policyconverters.lakeformation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassthroughPrincipalMapperTest {

    private final PassthroughPrincipalMapper mapper = new PassthroughPrincipalMapper();

    @Test
    void resolveUser_returnsRangerUserPrefix() {
        assertEquals("ranger-user:alice", mapper.resolveUser("alice").orElseThrow());
    }

    @Test
    void resolveGroup_returnsRangerGroupPrefix() {
        assertEquals("ranger-group:analysts", mapper.resolveGroup("analysts").orElseThrow());
    }

    @Test
    void resolveRole_returnsRangerRolePrefix() {
        assertEquals("ranger-role:admin", mapper.resolveRole("admin").orElseThrow());
    }

    @Test
    void resolveUser_nullInput_returnsEmpty() {
        assertTrue(mapper.resolveUser(null).isEmpty());
    }

    @Test
    void resolveGroup_nullInput_returnsEmpty() {
        assertTrue(mapper.resolveGroup(null).isEmpty());
    }

    @Test
    void resolveRole_nullInput_returnsEmpty() {
        assertTrue(mapper.resolveRole(null).isEmpty());
    }
}
