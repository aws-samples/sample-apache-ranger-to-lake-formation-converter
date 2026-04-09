package com.amazonaws.policyconverters.lakeformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LFPermissionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allPermissionValuesExist() {
        assertEquals(9, LFPermission.values().length);
    }

    @Test
    void fromValueIsCaseInsensitive() {
        assertEquals(LFPermission.SELECT, LFPermission.fromValue("select"));
        assertEquals(LFPermission.SELECT, LFPermission.fromValue("SELECT"));
    }

    @Test
    void fromValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> LFPermission.fromValue("UNKNOWN"));
    }

    @Test
    void jsonRoundTrip() throws Exception {
        for (LFPermission perm : LFPermission.values()) {
            String json = mapper.writeValueAsString(perm);
            LFPermission deserialized = mapper.readValue(json, LFPermission.class);
            assertEquals(perm, deserialized);
        }
    }
}
