package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagSyncConfigTest {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNullsProvided() {
        TagSyncConfig config = new TagSyncConfig(null, null, null);
        assertFalse(config.isEnabled());
        assertNull(config.getTagServiceName());
        assertEquals(0L, config.getTagSyncIntervalMs());
    }

    @Test
    void enabled_falseDoesNotRequireTagServiceName() {
        TagSyncConfig config = new TagSyncConfig(false, null, null);
        assertFalse(config.isEnabled());
    }

    @Test
    void enabled_trueWithTagServiceName() {
        TagSyncConfig config = new TagSyncConfig(true, "tagservice", 60000L);
        assertTrue(config.isEnabled());
        assertEquals("tagservice", config.getTagServiceName());
        assertEquals(60000L, config.getTagSyncIntervalMs());
    }

    @Test
    void zeroIntervalIsValid() {
        TagSyncConfig config = new TagSyncConfig(true, "tagservice", 0L);
        assertEquals(0L, config.getTagSyncIntervalMs());
    }

    @Test
    void jsonRoundTrip() throws Exception {
        TagSyncConfig original = new TagSyncConfig(true, "tagservice", 300000L);
        String json = jsonMapper.writeValueAsString(original);
        TagSyncConfig deserialized = jsonMapper.readValue(json, TagSyncConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTrip_disabled() throws Exception {
        TagSyncConfig original = new TagSyncConfig(false, null, 0L);
        String json = jsonMapper.writeValueAsString(original);
        TagSyncConfig deserialized = jsonMapper.readValue(json, TagSyncConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void yamlDeserialization() throws Exception {
        String yaml = "enabled: true\ntagServiceName: tagservice\ntagSyncIntervalMs: 60000\n";
        TagSyncConfig config = yamlMapper.readValue(yaml, TagSyncConfig.class);
        assertTrue(config.isEnabled());
        assertEquals("tagservice", config.getTagServiceName());
        assertEquals(60000L, config.getTagSyncIntervalMs());
    }

    @Test
    void yamlDeserialization_absentFieldsDefaultToFalseAndZero() throws Exception {
        String yaml = "enabled: false\n";
        TagSyncConfig config = yamlMapper.readValue(yaml, TagSyncConfig.class);
        assertFalse(config.isEnabled());
        assertNull(config.getTagServiceName());
        assertEquals(0L, config.getTagSyncIntervalMs());
    }

    @Test
    void equalsAndHashCode_sameValues() {
        TagSyncConfig a = new TagSyncConfig(true, "tagservice", 60000L);
        TagSyncConfig b = new TagSyncConfig(true, "tagservice", 60000L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqual_differentEnabled() {
        TagSyncConfig a = new TagSyncConfig(true, "tagservice", 0L);
        TagSyncConfig b = new TagSyncConfig(false, "tagservice", 0L);
        assertNotEquals(a, b);
    }

    @Test
    void notEqual_differentTagServiceName() {
        TagSyncConfig a = new TagSyncConfig(true, "tagservice", 0L);
        TagSyncConfig b = new TagSyncConfig(true, "other", 0L);
        assertNotEquals(a, b);
    }

    @Test
    void notEqual_differentInterval() {
        TagSyncConfig a = new TagSyncConfig(true, "tagservice", 60000L);
        TagSyncConfig b = new TagSyncConfig(true, "tagservice", 30000L);
        assertNotEquals(a, b);
    }

    @Test
    void toString_containsFields() {
        TagSyncConfig config = new TagSyncConfig(true, "tagservice", 300000L);
        String str = config.toString();
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("tagServiceName='tagservice'"));
        assertTrue(str.contains("tagSyncIntervalMs=300000"));
    }

    @Test
    void configValidator_enabledWithBlankTagServiceName_producesError() {
        TagSyncConfig config = new TagSyncConfig(true, null, 0L);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = new java.util.ArrayList<>();
        validator.validateTagSyncConfig(config, errors);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("tagServiceName")));
    }

    @Test
    void configValidator_enabledWithEmptyTagServiceName_producesError() {
        TagSyncConfig config = new TagSyncConfig(true, "   ", 0L);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = new java.util.ArrayList<>();
        validator.validateTagSyncConfig(config, errors);
        assertFalse(errors.isEmpty());
    }

    @Test
    void configValidator_enabledWithValidConfig_noErrors() {
        TagSyncConfig config = new TagSyncConfig(true, "tagservice", 60000L);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = new java.util.ArrayList<>();
        validator.validateTagSyncConfig(config, errors);
        assertTrue(errors.isEmpty());
    }

    @Test
    void configValidator_disabledWithBlankName_noErrors() {
        TagSyncConfig config = new TagSyncConfig(false, null, 0L);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = new java.util.ArrayList<>();
        validator.validateTagSyncConfig(config, errors);
        assertTrue(errors.isEmpty());
    }

    @Test
    void configValidator_negativeInterval_producesError() {
        TagSyncConfig config = new TagSyncConfig(true, "tagservice", -1L);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = new java.util.ArrayList<>();
        validator.validateTagSyncConfig(config, errors);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("tagSyncIntervalMs")));
    }
}
