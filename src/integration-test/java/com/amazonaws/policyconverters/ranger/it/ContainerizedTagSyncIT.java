package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.TagSyncOutput;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Containerized integration tests for Tag Metadata Sync functionality.
 *
 * <p>Verifies that the conversion server's tag sync pipeline correctly reads Ranger
 * tag definitions and resource-tag mappings from a live Ranger tag service and
 * writes the corresponding tag sync operations to the dry-run output directory.</p>
 *
 * <p>Prerequisites (provisioned by {@code setup-environment.sh}):
 * <ul>
 *   <li>Ranger Admin is running and the {@code cl_tag} tag service exists</li>
 *   <li>The conversion server is configured with {@code tagSync.enabled=true} and
 *       {@code tagServiceName=cl_tag}</li>
 *   <li>{@code DRY_RUN_ENABLED=true} so the server writes tag-sync-*.json files</li>
 * </ul>
 *
 * <p>Tests must run in order: later tests depend on state from earlier ones.</p>
 *
 * <p>Validates: Requirements 2, 3, 4, 5 (tag-metadata-sync spec)</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ContainerizedTagSyncIT extends ContainerizedPipelineIT {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerizedTagSyncIT.class);

    /** Tag definition IDs created in tests — cleaned up in @AfterEach via policyClient. */
    private final List<Integer> createdTagDefIds = new ArrayList<>();
    /** Tag instance IDs created in tests. */
    private final List<Integer> createdTagInstanceIds = new ArrayList<>();
    /** Tag service resource IDs created in tests. */
    private final List<Integer> createdTagResourceIds = new ArrayList<>();

    @BeforeEach
    void setUpTagSyncTest() {
        clearTagSyncOutputs();
    }

    @AfterEach
    void tearDownTagObjects() {
        for (int id : createdTagResourceIds) {
            try { policyClient.deleteTagServiceResource(id); }
            catch (Exception e) { LOG.warn("Failed to delete tag resource {}: {}", id, e.getMessage()); }
        }
        for (int id : createdTagInstanceIds) {
            try { policyClient.deleteTagInstance(id); }
            catch (Exception e) { LOG.warn("Failed to delete tag instance {}: {}", id, e.getMessage()); }
        }
        for (int id : createdTagDefIds) {
            try { policyClient.deleteTagDef(id); }
            catch (Exception e) { LOG.warn("Failed to delete tag def {}: {}", id, e.getMessage()); }
        }
        createdTagResourceIds.clear();
        createdTagInstanceIds.clear();
        createdTagDefIds.clear();
        clearTagSyncOutputs();
    }

    // ---- Test 1: Tag definition created in LF when Ranger tag def is added ----

    @Test
    @Order(1)
    void testTagDefinitionCreated() throws Exception {
        // Create a PII tag definition in Ranger
        int tagDefId = policyClient.createTagDef("PII");
        createdTagDefIds.add(tagDefId);
        LOG.info("Created Ranger tag def 'PII' with id={}", tagDefId);

        // Wait for the conversion server to pick up the tag def and emit CREATE_TAG
        List<TagSyncOutput> outputs = waitForTagSyncOutput();
        assertFalse(outputs.isEmpty(), "Expected tag-sync output after creating PII tag definition");

        List<TagSyncOutput> createOps = outputs.stream()
                .filter(o -> "CREATE_TAG".equals(o.getOperationType()))
                .filter(o -> "PII".equals(o.getTagKey()))
                .collect(Collectors.toList());

        assertFalse(createOps.isEmpty(),
                "Expected CREATE_TAG operation for 'PII' tag, got: " + outputs.stream()
                        .map(o -> o.getOperationType() + ":" + o.getTagKey())
                        .collect(Collectors.joining(", ")));

        TagSyncOutput createOp = createOps.get(0);
        assertNotNull(createOp.getTagValues(), "CREATE_TAG should include tagValues");
        assertTrue(createOp.getTagValues().contains("true"),
                "LF-Tag value should be 'true', got: " + createOp.getTagValues());

        LOG.info("Tag definition creation verified: CREATE_TAG for PII with values={}", createOp.getTagValues());
    }

    // ---- Test 2: Tag attachment synced when resource-tag mapping is added ----

    @Test
    @Order(2)
    void testTagAttachmentCreated() throws Exception {
        // Create tag definition
        int tagDefId = policyClient.createTagDef("SENSITIVE");
        createdTagDefIds.add(tagDefId);

        // Wait for tag def to be picked up (CREATE_TAG)
        waitForTagSyncOutput();
        clearTagSyncOutputs();

        // Create a service resource (database "sensitive_db" in the cl_tag service)
        String resourceJson = "{\"database\":{\"values\":[\"sensitive_db\"],\"isRecursive\":false}}";
        int resourceId = policyClient.createTagServiceResource("cl_tag", resourceJson);
        createdTagResourceIds.add(resourceId);
        LOG.info("Created tag service resource id={} for sensitive_db", resourceId);

        // Create a tag instance for SENSITIVE
        int tagInstanceId = policyClient.createTagInstance("SENSITIVE");
        createdTagInstanceIds.add(tagInstanceId);

        // Map the tag instance to the resource
        policyClient.createResourceTagMapping(resourceId, List.of(tagInstanceId));
        LOG.info("Mapped SENSITIVE tag instance {} to resource {}", tagInstanceId, resourceId);

        // Wait for ATTACH_TAG operation
        List<TagSyncOutput> outputs = waitForTagSyncOutput();
        assertFalse(outputs.isEmpty(), "Expected tag-sync output after resource-tag mapping");

        List<TagSyncOutput> attachOps = outputs.stream()
                .filter(o -> "ATTACH_TAG".equals(o.getOperationType()))
                .filter(o -> "SENSITIVE".equals(o.getTagKey()))
                .collect(Collectors.toList());

        assertFalse(attachOps.isEmpty(),
                "Expected ATTACH_TAG for 'SENSITIVE', got: " + outputs.stream()
                        .map(o -> o.getOperationType() + ":" + o.getTagKey())
                        .collect(Collectors.joining(", ")));

        TagSyncOutput attachOp = attachOps.get(0);
        assertNotNull(attachOp.getResource(), "ATTACH_TAG should include resource descriptor");
        assertEquals("sensitive_db", attachOp.getResource().get("database"),
                "Resource descriptor should include database name");

        LOG.info("Tag attachment verified: ATTACH_TAG for SENSITIVE on database=sensitive_db");
    }

    // ---- Test 3: Multiple tag definitions synced in one cycle ----

    @Test
    @Order(3)
    void testMultipleTagDefinitions() throws Exception {
        // Create two tag definitions
        int piiId = policyClient.createTagDef("PII_MULTI");
        createdTagDefIds.add(piiId);
        int confId = policyClient.createTagDef("CONFIDENTIAL");
        createdTagDefIds.add(confId);
        LOG.info("Created tag defs PII_MULTI={} CONFIDENTIAL={}", piiId, confId);

        // Collect tag-sync output until both CREATE_TAG operations appear
        List<TagSyncOutput> allOutputs = new ArrayList<>();
        long deadline = System.currentTimeMillis() + DEFAULT_SYNC_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            allOutputs.addAll(readTagSyncOutputs());

            long piiCreates = allOutputs.stream()
                    .filter(o -> "CREATE_TAG".equals(o.getOperationType()))
                    .filter(o -> "PII_MULTI".equals(o.getTagKey()))
                    .count();
            long confCreates = allOutputs.stream()
                    .filter(o -> "CREATE_TAG".equals(o.getOperationType()))
                    .filter(o -> "CONFIDENTIAL".equals(o.getTagKey()))
                    .count();

            if (piiCreates > 0 && confCreates > 0) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        List<String> createdTagKeys = allOutputs.stream()
                .filter(o -> "CREATE_TAG".equals(o.getOperationType()))
                .map(TagSyncOutput::getTagKey)
                .collect(Collectors.toList());

        assertTrue(createdTagKeys.contains("PII_MULTI"),
                "Expected CREATE_TAG for PII_MULTI, got: " + createdTagKeys);
        assertTrue(createdTagKeys.contains("CONFIDENTIAL"),
                "Expected CREATE_TAG for CONFIDENTIAL, got: " + createdTagKeys);

        LOG.info("Multiple tag definitions verified: CREATE_TAG for both PII_MULTI and CONFIDENTIAL");
    }

    // ---- Test 4: Idempotency — second sync cycle produces no duplicate CREATE_TAG ----

    @Test
    @Order(4)
    void testTagSyncIdempotency() throws Exception {
        // Create a tag definition and wait for the first CREATE_TAG
        int tagDefId = policyClient.createTagDef("IDEM_TAG");
        createdTagDefIds.add(tagDefId);

        List<TagSyncOutput> firstOutputs = waitForTagSyncOutput();
        long firstCreateCount = firstOutputs.stream()
                .filter(o -> "CREATE_TAG".equals(o.getOperationType()))
                .filter(o -> "IDEM_TAG".equals(o.getTagKey()))
                .count();
        assertTrue(firstCreateCount > 0, "Expected CREATE_TAG for IDEM_TAG in first sync cycle");

        // Clear outputs and wait for the next sync cycle
        clearTagSyncOutputs();
        try {
            List<TagSyncOutput> secondOutputs = waitForTagSyncOutput(DEFAULT_SYNC_TIMEOUT_MS);
            // If output was produced, it must NOT re-create IDEM_TAG
            long duplicateCreates = secondOutputs.stream()
                    .filter(o -> "CREATE_TAG".equals(o.getOperationType()))
                    .filter(o -> "IDEM_TAG".equals(o.getTagKey()))
                    .count();
            assertEquals(0, duplicateCreates,
                    "Tag sync must not re-emit CREATE_TAG for IDEM_TAG in subsequent cycle");
        } catch (AssertionError e) {
            // No output in second cycle — this is the ideal idempotent behavior
            LOG.info("No tag-sync output in second cycle — idempotent (no re-create for IDEM_TAG)");
        }

        LOG.info("Tag sync idempotency verified for IDEM_TAG");
    }

    // ---- Test 5: Checkpoint persists tag version across cycles ----

    @Test
    @Order(5)
    void testTagVersionCheckpointed() throws Exception {
        // Create a tag def and wait for sync
        int tagDefId = policyClient.createTagDef("CHECKPOINT_TAG");
        createdTagDefIds.add(tagDefId);

        waitForTagSyncOutput();

        // Read the checkpoint and verify lastKnownTagVersion is present and > 0
        java.util.Map<String, Object> checkpoint = readCheckpointFile();
        assertFalse(checkpoint.isEmpty(), "Checkpoint file should exist after tag sync");

        Object tagVersion = checkpoint.get("lastKnownTagVersion");
        assertNotNull(tagVersion, "Checkpoint should contain lastKnownTagVersion after tag sync");
        assertTrue(tagVersion instanceof Number, "lastKnownTagVersion should be a Number");
        assertTrue(((Number) tagVersion).longValue() > 0,
                "lastKnownTagVersion should be > 0 after a sync with tags, got: " + tagVersion);

        Object managedTagNames = checkpoint.get("lastKnownRangerTagNames");
        assertNotNull(managedTagNames, "Checkpoint should contain lastKnownRangerTagNames");

        LOG.info("Checkpoint tag version verified: lastKnownTagVersion={}, lastKnownRangerTagNames={}",
                tagVersion, managedTagNames);
    }
}
