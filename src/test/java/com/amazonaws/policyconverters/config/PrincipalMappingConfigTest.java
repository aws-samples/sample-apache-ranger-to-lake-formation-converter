package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalMappingConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allMappingsSet() {
        Map<String, String> users = new HashMap<>();
        users.put("alice", "arn:aws:iam::123456789012:user/alice");
        Map<String, String> groups = new HashMap<>();
        groups.put("analysts", "arn:aws:iam::123456789012:role/AnalystRole");
        Map<String, String> roles = new HashMap<>();
        roles.put("admin_role", "arn:aws:iam::123456789012:role/AdminRole");

        PrincipalMappingConfig config = new PrincipalMappingConfig(users, groups, roles);
        assertEquals("arn:aws:iam::123456789012:user/alice", config.getUserMappings().get("alice"));
        assertEquals("arn:aws:iam::123456789012:role/AnalystRole", config.getGroupMappings().get("analysts"));
        assertEquals("arn:aws:iam::123456789012:role/AdminRole", config.getRoleMappings().get("admin_role"));
    }

    @Test
    void nullMappingsDefaultToEmpty() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        assertNotNull(config.getUserMappings());
        assertTrue(config.getUserMappings().isEmpty());
        assertNotNull(config.getGroupMappings());
        assertTrue(config.getGroupMappings().isEmpty());
        assertNotNull(config.getRoleMappings());
        assertTrue(config.getRoleMappings().isEmpty());
    }

    @Test
    void mapsAreImmutable() {
        Map<String, String> users = new HashMap<>();
        users.put("bob", "arn:aws:iam::123456789012:user/bob");
        PrincipalMappingConfig config = new PrincipalMappingConfig(users, null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> config.getUserMappings().put("eve", "arn:aws:iam::123456789012:user/eve"));
    }

    @Test
    void equalsAndHashCode() {
        Map<String, String> users = Collections.singletonMap("alice", "arn:aws:iam::123456789012:user/alice");
        PrincipalMappingConfig a = new PrincipalMappingConfig(users, null, null);
        PrincipalMappingConfig b = new PrincipalMappingConfig(users, null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        Map<String, String> users1 = Collections.singletonMap("alice", "arn:aws:iam::123456789012:user/alice");
        Map<String, String> users2 = Collections.singletonMap("bob", "arn:aws:iam::123456789012:user/bob");
        PrincipalMappingConfig a = new PrincipalMappingConfig(users1, null, null);
        PrincipalMappingConfig b = new PrincipalMappingConfig(users2, null, null);
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        Map<String, String> users = new HashMap<>();
        users.put("alice", "arn:aws:iam::123456789012:user/alice");
        users.put("bob", "arn:aws:iam::123456789012:user/bob");
        Map<String, String> groups = Collections.singletonMap("analysts", "arn:aws:iam::123456789012:role/Analysts");
        Map<String, String> roles = Collections.singletonMap("admin", "arn:aws:iam::123456789012:role/Admin");

        PrincipalMappingConfig original = new PrincipalMappingConfig(users, groups, roles);
        String json = mapper.writeValueAsString(original);
        PrincipalMappingConfig deserialized = mapper.readValue(json, PrincipalMappingConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithEmptyMappings() throws Exception {
        PrincipalMappingConfig original = new PrincipalMappingConfig(null, null, null);
        String json = mapper.writeValueAsString(original);
        PrincipalMappingConfig deserialized = mapper.readValue(json, PrincipalMappingConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringContainsMappings() {
        Map<String, String> users = Collections.singletonMap("alice", "arn:aws:iam::123456789012:user/alice");
        PrincipalMappingConfig config = new PrincipalMappingConfig(users, null, null);
        String str = config.toString();
        assertTrue(str.contains("userMappings="));
        assertTrue(str.contains("alice"));
    }

    // --- IDC field tests ---

    @Test
    void noTypeField_defaultsToStatic() throws Exception {
        // Old YAML / JSON with no "type" field should deserialize as STATIC
        String json = "{\"userMappings\":{\"alice\":\"arn:aws:iam::123456789012:user/alice\"}}";
        PrincipalMappingConfig config = mapper.readValue(json, PrincipalMappingConfig.class);
        assertEquals(PrincipalMapperType.STATIC, config.getType());
    }

    @Test
    void identityCenterType_roundTrips() throws Exception {
        IdentityCenterConfig idcConfig = new IdentityCenterConfig("d-test123", "us-east-1", "123456789012", 30);
        PrincipalMappingConfig original = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, idcConfig);

        String json = mapper.writeValueAsString(original);
        PrincipalMappingConfig deserialized = mapper.readValue(json, PrincipalMappingConfig.class);

        assertEquals(PrincipalMapperType.IDENTITY_CENTER, deserialized.getType());
        assertNotNull(deserialized.getIdcConfig());
        assertEquals("d-test123", deserialized.getIdcConfig().getIdentityStoreId());
        assertEquals("us-east-1", deserialized.getIdcConfig().getRegion());
        assertEquals("123456789012", deserialized.getIdcConfig().getAccountId());
        assertEquals(30, deserialized.getIdcConfig().getCacheTtlMinutes());
        assertEquals(original, deserialized);
    }

    @Test
    void notEqualWhenTypeDiffers() {
        Map<String, String> users = Collections.singletonMap("alice", "arn:aws:iam::123456789012:user/alice");
        PrincipalMappingConfig staticConfig = new PrincipalMappingConfig(users, null, null,
                PrincipalMapperType.STATIC, null);
        PrincipalMappingConfig idcConfig = new PrincipalMappingConfig(users, null, null,
                PrincipalMapperType.IDENTITY_CENTER, null);

        assertNotEquals(staticConfig, idcConfig);
    }

    // --- delegates field tests ---

    @Test
    void delegates_defaultsToEmptyList() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        assertNotNull(config.getDelegates());
        assertTrue(config.getDelegates().isEmpty());
    }

    @Test
    void delegates_jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{"
                + "\"type\": \"COMPOSITE\","
                + "\"delegates\": ["
                + "  {\"type\": \"STATIC\", \"userMappings\": {\"alice\": \"arn:aws:iam::123:role/alice\"}},"
                + "  {\"type\": \"IDENTITY_CENTER\", \"idcConfig\": {"
                + "    \"identityStoreId\": \"d-test\", \"region\": \"us-east-1\","
                + "    \"accountId\": \"123456789012\", \"cacheTtlMinutes\": 60}}"
                + "]}";
        PrincipalMappingConfig config = mapper.readValue(json, PrincipalMappingConfig.class);
        assertEquals(PrincipalMapperType.COMPOSITE, config.getType());
        assertEquals(2, config.getDelegates().size());
        assertEquals(PrincipalMapperType.STATIC, config.getDelegates().get(0).getType());
        assertEquals("arn:aws:iam::123:role/alice",
                config.getDelegates().get(0).getUserMappings().get("alice"));
        assertEquals(PrincipalMapperType.IDENTITY_CENTER, config.getDelegates().get(1).getType());
    }

    @Test
    void delegates_existingConfigsUnaffected() throws Exception {
        // Existing STATIC config without delegates still deserializes unchanged
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"type\": \"STATIC\", \"userMappings\": {\"bob\": \"arn:aws:iam::123:role/bob\"}}";
        PrincipalMappingConfig config = mapper.readValue(json, PrincipalMappingConfig.class);
        assertEquals(PrincipalMapperType.STATIC, config.getType());
        assertTrue(config.getDelegates().isEmpty());
        assertEquals("arn:aws:iam::123:role/bob", config.getUserMappings().get("bob"));
        // delegates must be absent from the serialized form — NON_NULL excludes null fields
        String serialized = mapper.writeValueAsString(config);
        assertFalse(serialized.contains("delegates"),
                "delegates key must be absent from serialized STATIC config: " + serialized);
    }
}
