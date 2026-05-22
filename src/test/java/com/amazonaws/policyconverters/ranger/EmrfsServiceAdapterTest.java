package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmrfsServiceAdapter.
 */
class EmrfsServiceAdapterTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");
    private EmrfsServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new EmrfsServiceAdapter(AWS_CONTEXT);
    }

    // --- mapAccessTypeToCedarActions ---

    @Test
    void getObjectMapsToS3GetObject() {
        assertEquals(Set.of("s3:GetObject"), adapter.mapAccessTypeToCedarActions("GetObject"));
    }

    @Test
    void putObjectMapsToS3PutObject() {
        assertEquals(Set.of("s3:PutObject"), adapter.mapAccessTypeToCedarActions("PutObject"));
    }

    @Test
    void listObjectsMapsToS3ListObjects() {
        assertEquals(Set.of("s3:ListObjects"), adapter.mapAccessTypeToCedarActions("ListObjects"));
    }

    @Test
    void deleteObjectMapsToS3DeleteObject() {
        assertEquals(Set.of("s3:DeleteObject"), adapter.mapAccessTypeToCedarActions("DeleteObject"));
    }

    @Test
    void unknownAccessTypeReturnsEmptySet() {
        Set<String> result = adapter.mapAccessTypeToCedarActions("UnknownType");
        assertNotNull(result, "Result must not be null");
        assertTrue(result.isEmpty(), "Unknown access type should return empty set");
    }

    @Test
    void nullAccessTypeReturnsEmptySet() {
        assertTrue(adapter.mapAccessTypeToCedarActions(null).isEmpty());
    }

    @Test
    void emptyAccessTypeReturnsEmptySet() {
        assertTrue(adapter.mapAccessTypeToCedarActions("").isEmpty());
    }

    // --- buildEntityRef ---

    @Test
    void buildEntityRefWithPlainPathProducesS3Arn() {
        RangerPolicy policy = buildPolicyWithPath("my-bucket/data/file.parquet", false);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "sthreeresource");

        assertNotNull(ref);
        assertEquals("S3::Object", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/data/file.parquet", ref.getEntityId());
    }

    @Test
    void buildEntityRefWithIsRecursiveTrueAppendsWildcard() {
        RangerPolicy policy = buildPolicyWithPath("my-bucket/data", true);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "sthreeresource");

        assertNotNull(ref);
        assertEquals("S3::Object", ref.getEntityType());
        assertTrue(ref.getEntityId().endsWith("/*"),
                "ARN should end with /* for recursive resources");
        assertEquals("arn:aws:s3:::my-bucket/data/*", ref.getEntityId());
    }

    @Test
    void buildEntityRefWithWildcardPathPreservesWildcard() {
        RangerPolicy policy = buildPolicyWithPath("my-bucket/data/*", false);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "sthreeresource");

        assertNotNull(ref);
        assertEquals("S3::Object", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/data/*", ref.getEntityId());
    }

    @Test
    void buildEntityRefWithMissingResourceKeyReturnsNull() {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test");
        policy.setService("amazon-emr-emrfs");
        policy.setResources(Collections.emptyMap());

        CedarEntityRef ref = adapter.buildEntityRef(policy, "sthreeresource");
        assertNull(ref, "Should return null when resource key is absent");
    }

    // --- Helper ---

    private static RangerPolicy buildPolicyWithPath(String path, boolean isRecursive) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test_emrfs_policy");
        policy.setService("amazon-emr-emrfs");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource res = new RangerPolicyResource();
        res.setValues(Collections.singletonList(path));
        res.setIsRecursive(isRecursive);
        resources.put("sthreeresource", res);
        policy.setResources(resources);
        return policy;
    }
}
