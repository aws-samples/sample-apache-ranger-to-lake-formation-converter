package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AwsConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allFieldsSet() {
        AwsConfig config = new AwsConfig(
                "us-east-1", "123456789012",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "arn:aws:iam::123456789012:role/MyRole");
        assertEquals("us-east-1", config.getRegion());
        assertEquals("123456789012", config.getCatalogId());
        assertEquals("AKIAIOSFODNN7EXAMPLE", config.getAccessKey());
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", config.getSecretKey());
        assertEquals("arn:aws:iam::123456789012:role/MyRole", config.getRoleArn());
    }

    @Test
    void roleBasedWithoutStaticCredentials() {
        AwsConfig config = new AwsConfig(
                "us-west-2", "123456789012",
                null, null,
                "arn:aws:iam::123456789012:role/LFRole");
        assertNull(config.getAccessKey());
        assertNull(config.getSecretKey());
        assertEquals("arn:aws:iam::123456789012:role/LFRole", config.getRoleArn());
    }

    @Test
    void equalsAndHashCode() {
        AwsConfig a = new AwsConfig("us-east-1", "123", "ak", "sk", null);
        AwsConfig b = new AwsConfig("us-east-1", "123", "ak", "sk", null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        AwsConfig a = new AwsConfig("us-east-1", "123", null, null, null);
        AwsConfig b = new AwsConfig("us-west-2", "123", null, null, null);
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        AwsConfig original = new AwsConfig(
                "us-east-1", "123456789012",
                "AKID", "SECRET", "arn:aws:iam::123456789012:role/R");
        String json = mapper.writeValueAsString(original);
        AwsConfig deserialized = mapper.readValue(json, AwsConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithNulls() throws Exception {
        AwsConfig original = new AwsConfig("eu-west-1", "999", null, null, null);
        String json = mapper.writeValueAsString(original);
        AwsConfig deserialized = mapper.readValue(json, AwsConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringMasksSensitiveFields() {
        AwsConfig config = new AwsConfig("us-east-1", "123", "AKID", "SECRET", null);
        String str = config.toString();
        assertTrue(str.contains("region='us-east-1'"));
        assertTrue(str.contains("accessKey='****'"));
        assertTrue(str.contains("secretKey='****'"));
        assertFalse(str.contains("AKID"));
        assertFalse(str.contains("SECRET"));
    }
}
