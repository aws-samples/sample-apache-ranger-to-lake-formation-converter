package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.model.TagSyncResult;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.util.ServiceTags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.EntityNotFoundException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagMetadataSyncerTest {

    @Mock
    private LakeFormationClient lfClient;

    private TagMetadataSyncer syncer;

    private static final String CATALOG_ID = "123456789012";

    @BeforeEach
    void setUp() {
        syncer = new TagMetadataSyncer(lfClient, CATALOG_ID);
    }

    // --- listLFTagKeys failure → abort with failure result ---

    @Test
    void listLFTagKeys_failure_returnsFailureResult() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenThrow(new RuntimeException("LF unavailable"));

        TagSyncResult result = syncer.sync(makeServiceTags(Set.of("PII")), Set.of());

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        verify(lfClient, never()).createLFTag(any(), any(), any());
    }

    // --- new tag → CreateLFTag ---

    @Test
    void newTag_createsLFTag() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of());
        // No resources → getResourceLFTags is not called

        TagSyncResult result = syncer.sync(makeServiceTags(Set.of("PII")), Set.of());

        verify(lfClient).createLFTag(CATALOG_ID, "PII", List.of("true"));
        assertTrue(result.isSuccess());
        assertEquals(1, result.getTagsCreated());
    }

    @Test
    void existingTag_notCreatedAgain() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII"));
        // No resources → getResourceLFTags is not called

        syncer.sync(makeServiceTags(Set.of("PII")), Set.of("PII"));

        verify(lfClient, never()).createLFTag(any(), any(), any());
    }

    // --- owned absent tag → DeleteLFTag ---

    @Test
    void ownedAbsentTag_deletesLFTag() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("OLD_TAG"));
        // No desired resources, so actualAttachments will be empty
        ServiceTags desired = makeServiceTags(Set.of());

        TagSyncResult result = syncer.sync(desired, Set.of("OLD_TAG"));

        verify(lfClient).deleteLFTag(CATALOG_ID, "OLD_TAG");
        assertEquals(1, result.getTagsDeleted());
    }

    // --- external tag (not in lfManagedTags) → never deleted ---

    @Test
    void externalTag_notDeleted() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("EXTERNAL"));
        ServiceTags desired = makeServiceTags(Set.of());

        syncer.sync(desired, Set.of()); // lfManagedTags is empty — EXTERNAL is not owned

        verify(lfClient, never()).deleteLFTag(any(), eq("EXTERNAL"));
    }

    // --- new attachment → AddLFTagsToResource ---

    @Test
    void newAttachment_addsLFTagsToResource() throws Exception {
        LFResource resource = new LFResource(null, "mydb", "mytable", null, null);
        ServiceTags desired = makeServiceTagsWithAttachment("PII", resource);

        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII"));
        when(lfClient.getResourceLFTags(eq(resource), eq(CATALOG_ID))).thenReturn(Map.of());

        TagSyncResult result = syncer.sync(desired, Set.of("PII"));

        verify(lfClient).addLFTagsToResource(eq(resource), eq(Map.of("PII", "true")), eq(CATALOG_ID));
        assertEquals(1, result.getAttachmentsAdded());
    }

    @Test
    void existingAttachment_notAddedAgain() throws Exception {
        LFResource resource = new LFResource(null, "mydb", "mytable", null, null);
        ServiceTags desired = makeServiceTagsWithAttachment("PII", resource);

        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII"));
        when(lfClient.getResourceLFTags(eq(resource), eq(CATALOG_ID)))
                .thenReturn(Map.of("PII", "true"));

        TagSyncResult result = syncer.sync(desired, Set.of("PII"));

        verify(lfClient, never()).addLFTagsToResource(any(), any(), any());
        assertEquals(0, result.getAttachmentsAdded());
    }

    // --- removed owned attachment → RemoveLFTagsFromResource ---

    @Test
    @SuppressWarnings("unchecked")
    void removedOwnedAttachment_removesFromResource() throws Exception {
        // Resource has PUBLIC in desired, but PII is still attached in actual and owned.
        // syncer fetches actual for the resource (because it's in desiredAttachments) and removes PII.
        LFResource resource = new LFResource(null, "mydb", "mytable", null, null);
        ServiceTags desired = makeServiceTagsWithAttachment("PUBLIC", resource);

        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII", "PUBLIC"));
        when(lfClient.getResourceLFTags(eq(resource), eq(CATALOG_ID)))
                .thenReturn(Map.of("PII", "true", "PUBLIC", "true"));

        // PII is in lfManagedTags but absent from desired resource assignment → should be removed
        TagSyncResult result = syncer.sync(desired, Set.of("PII", "PUBLIC"));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(lfClient).removeLFTagsFromResource(eq(resource), captor.capture(), eq(CATALOG_ID));
        assertTrue(captor.getValue().contains("PII"), "PII should be removed");
        assertFalse(captor.getValue().contains("PUBLIC"), "PUBLIC should not be removed");
        assertEquals(1, result.getAttachmentsRemoved());
    }

    // --- external attachment (not in lfManagedTags) → not removed ---

    @Test
    void externalAttachment_notRemoved() throws Exception {
        // Resource has PII in desired. EXTERNAL is also in actual but not in lfManagedTags.
        LFResource resource = new LFResource(null, "mydb", "mytable", null, null);
        ServiceTags desired = makeServiceTagsWithAttachment("PII", resource);

        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII", "EXTERNAL"));
        when(lfClient.getResourceLFTags(eq(resource), eq(CATALOG_ID)))
                .thenReturn(Map.of("PII", "true", "EXTERNAL", "true"));

        syncer.sync(desired, Set.of("PII")); // EXTERNAL is not in lfManagedTags

        verify(lfClient, never()).removeLFTagsFromResource(any(), any(), any());
    }

    // --- getResourceLFTags failure → abort ---

    @Test
    void getResourceLFTags_failure_abortsWithFailureResult() throws Exception {
        // Resource has a tag assignment in desired → triggers getResourceLFTags
        LFResource resource = new LFResource(null, "mydb", "mytable", null, null);
        ServiceTags desired = makeServiceTagsWithAttachment("PII", resource);

        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII"));
        when(lfClient.getResourceLFTags(any(), eq(CATALOG_ID)))
                .thenThrow(EntityNotFoundException.builder().message("not found").build());

        TagSyncResult result = syncer.sync(desired, Set.of("PII"));

        assertFalse(result.isSuccess());
        verify(lfClient, never()).addLFTagsToResource(any(), any(), any());
        verify(lfClient, never()).removeLFTagsFromResource(any(), any(), any());
        verify(lfClient, never()).deleteLFTag(any(), any());
    }

    // --- createLFTag failure → continues, increments failed ---

    @Test
    void createLFTag_failure_continuedWithFailureCount() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of());
        doThrow(new RuntimeException("create failed"))
                .when(lfClient).createLFTag(eq(CATALOG_ID), eq("PII"), any());
        // No resources, so no actual attachments fetched
        ServiceTags desired = makeServiceTags(Set.of("PII"));

        TagSyncResult result = syncer.sync(desired, Set.of());

        assertTrue(result.isSuccess()); // overall success but with failures
        assertEquals(1, result.getFailed());
    }

    // --- tag with remaining attachments → deferred (not deleted) ---

    @Test
    void tagWithRemainingAttachments_notDeleted() throws Exception {
        // PII is managed+in LF, but not in desired tag defs → would normally be deleted.
        // However, PUBLIC is in desired and the resource still carries PII in actual.
        // The syncer should defer deletion of PII since it still has resource attachments.
        LFResource resource = new LFResource(null, "mydb", "mytable", null, null);
        ServiceTags desired = makeServiceTagsWithAttachment("PUBLIC", resource);

        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of("PII", "PUBLIC"));
        when(lfClient.getResourceLFTags(eq(resource), eq(CATALOG_ID)))
                .thenReturn(Map.of("PII", "true", "PUBLIC", "true"));

        // PII is managed but absent from desired tag defs → toDelete includes PII
        // But resource still has PII in actual → delete deferred
        syncer.sync(desired, Set.of("PII", "PUBLIC"));

        verify(lfClient, never()).deleteLFTag(any(), eq("PII"));
    }

    // --- null desired → returns success with zeros ---

    @Test
    void nullDesired_returnsSuccessWithZeros() throws Exception {
        when(lfClient.listLFTagKeys(CATALOG_ID)).thenReturn(List.of());
        TagSyncResult result = syncer.sync(null, Set.of());
        assertTrue(result.isSuccess());
        assertEquals(0, result.getTagsCreated());
        assertEquals(0, result.getTagsDeleted());
    }

    // --- mapToLFResource static helper ---

    @Test
    void mapToLFResource_databaseOnly() {
        RangerServiceResource resource = makeResource("mydb", null, null);
        LFResource lf = TagMetadataSyncer.mapToLFResource(resource);
        assertNotNull(lf);
        assertEquals("mydb", lf.getDatabaseName());
        assertNull(lf.getTableName());
    }

    @Test
    void mapToLFResource_noDatabase_returnsNull() {
        RangerServiceResource resource = makeResource(null, "mytable", null);
        assertNull(TagMetadataSyncer.mapToLFResource(resource));
    }

    // --- helpers ---

    private ServiceTags makeServiceTags(Set<String> tagNames) {
        Map<Long, RangerTagDef> tagDefs = new HashMap<>();
        long id = 1L;
        for (String name : tagNames) {
            RangerTagDef def = new RangerTagDef(name);
            def.setId(id++);
            tagDefs.put(def.getId(), def);
        }
        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(1L);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(tagDefs);
        tags.setTags(new HashMap<>());
        tags.setServiceResources(List.of());
        tags.setResourceToTagIds(new HashMap<>());
        return tags;
    }

    private ServiceTags makeServiceTagsWithAttachment(String tagName, LFResource lfResource) {
        RangerTagDef def = new RangerTagDef(tagName);
        def.setId(1L);

        RangerTag tag = new RangerTag(tagName, null);
        tag.setId(10L);

        RangerServiceResource resource = makeResource(
                lfResource.getDatabaseName(), lfResource.getTableName(), null);
        resource.setId(100L);

        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(1L);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(new HashMap<>(Map.of(1L, def)));
        tags.setTags(new HashMap<>(Map.of(10L, tag)));
        tags.setServiceResources(List.of(resource));
        tags.setResourceToTagIds(new HashMap<>(Map.of(100L, List.of(10L))));
        return tags;
    }

    private RangerServiceResource makeResource(String db, String table, String column) {
        Map<String, RangerPolicyResource> elements = new HashMap<>();
        if (db != null) elements.put("database", new RangerPolicyResource(db));
        if (table != null) elements.put("table", new RangerPolicyResource(table));
        if (column != null) elements.put("column", new RangerPolicyResource(column));
        RangerServiceResource resource = new RangerServiceResource();
        resource.setResourceElements(elements);
        return resource;
    }
}
