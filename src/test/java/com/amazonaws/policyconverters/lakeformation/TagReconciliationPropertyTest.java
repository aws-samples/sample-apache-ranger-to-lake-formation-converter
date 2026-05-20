package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.model.TagSyncResult;
import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.util.ServiceTags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Feature: tag-metadata-sync
// Property 1: Idempotency — desired equals actual → zero LF ops
// Property 2: No-touch invariant — external tag keys are never deleted or detached
// Property 3: Desired superset — only creates, no deletes when desired > actual
// Property 4: Actual superset of owned — only deletes, no creates when actual > desired
// Property 7: Failure isolation — getResourceLFTags throws → abort, zero LF write calls

@Tag("tag-metadata-sync")
class TagReconciliationPropertyTest {

    // -----------------------------------------------------------------------
    // Property 1: Idempotency
    // desired state == actual state → zero LF operations
    // -----------------------------------------------------------------------
    @Property(tries = 50)
    void idempotency_desiredEqualsActual_zeroOps(
            @ForAll("tagNameSets") Set<String> tagNames
    ) throws Exception {
        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        TagMetadataSyncer syncer = new TagMetadataSyncer(lfClient, "123456789012");

        // All tags already exist in LF and are managed by us
        when(lfClient.listLFTagKeys("123456789012"))
                .thenReturn(new ArrayList<>(tagNames));
        // No resources → no attachment calls needed

        ServiceTags desired = buildServiceTags(tagNames);
        TagSyncResult result = syncer.sync(desired, tagNames);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getTagsCreated(), "No creates when already in sync");
        assertEquals(0, result.getTagsDeleted(), "No deletes when already in sync");
        assertEquals(0, result.getAttachmentsAdded());
        assertEquals(0, result.getAttachmentsRemoved());
        verify(lfClient, never()).createLFTag(any(), any(), any());
        verify(lfClient, never()).deleteLFTag(any(), any());
    }

    // -----------------------------------------------------------------------
    // Property 2: No-touch invariant
    // Tags not in lfManagedTags are never deleted or detached
    // -----------------------------------------------------------------------
    @Property(tries = 50)
    void noTouchInvariant_externalTagsNeverDeleted(
            @ForAll("tagNameSets") Set<String> externalTags,
            @ForAll("tagNameSets") Set<String> managedTags
    ) throws Exception {
        // Ensure disjoint sets
        Set<String> external = new HashSet<>(externalTags);
        external.removeAll(managedTags);
        if (external.isEmpty()) return; // skip if no external tags

        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        TagMetadataSyncer syncer = new TagMetadataSyncer(lfClient, "123456789012");

        // Actual LF has both external and managed tags
        List<String> actualKeys = new ArrayList<>(external);
        actualKeys.addAll(managedTags);
        when(lfClient.listLFTagKeys("123456789012")).thenReturn(actualKeys);

        // Desired: only managed tags (external are absent from desired)
        ServiceTags desired = buildServiceTags(managedTags);
        syncer.sync(desired, managedTags);

        // None of the external tags should be deleted
        for (String extTag : external) {
            verify(lfClient, never()).deleteLFTag(any(), eq(extTag));
        }
    }

    // -----------------------------------------------------------------------
    // Property 3: Desired superset → only creates, no deletes
    // -----------------------------------------------------------------------
    @Property(tries = 50)
    void desiredSuperset_onlyCreates(
            @ForAll("tagNameSets") Set<String> existing,
            @ForAll("tagNameSets") Set<String> newTags
    ) throws Exception {
        Set<String> allDesired = new HashSet<>(existing);
        allDesired.addAll(newTags);
        if (newTags.isEmpty()) return; // need at least one new tag

        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        TagMetadataSyncer syncer = new TagMetadataSyncer(lfClient, "123456789012");

        // Actual = existing only
        when(lfClient.listLFTagKeys("123456789012"))
                .thenReturn(new ArrayList<>(existing));

        ServiceTags desired = buildServiceTags(allDesired);
        TagSyncResult result = syncer.sync(desired, existing);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getTagsDeleted(), "No deletes when desired is superset");
        verify(lfClient, never()).deleteLFTag(any(), any());
    }

    // -----------------------------------------------------------------------
    // Property 4: Actual superset of owned → only deletes, no creates
    // -----------------------------------------------------------------------
    @Property(tries = 50)
    void actualSuperset_onlyDeletes(
            @ForAll("tagNameSets") Set<String> desired,
            @ForAll("tagNameSets") Set<String> extra
    ) throws Exception {
        Set<String> managed = new HashSet<>(desired);
        managed.addAll(extra);
        Set<String> onlyExtra = new HashSet<>(extra);
        onlyExtra.removeAll(desired);
        if (onlyExtra.isEmpty()) return; // need at least one extra

        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        TagMetadataSyncer syncer = new TagMetadataSyncer(lfClient, "123456789012");

        // Actual = desired + extra (all managed)
        when(lfClient.listLFTagKeys("123456789012")).thenReturn(new ArrayList<>(managed));
        // No resources so getResourceLFTags won't be called

        ServiceTags desiredTags = buildServiceTags(desired);
        TagSyncResult result = syncer.sync(desiredTags, managed);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getTagsCreated(), "No creates when actual is superset");
        verify(lfClient, never()).createLFTag(any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // Property 7: Failure isolation — getResourceLFTags throws → abort
    // -----------------------------------------------------------------------
    @Property(tries = 30)
    void failureIsolation_getResourceLFTagsThrows_noWriteCalls(
            @ForAll("tagNameSets") Set<String> tagNames
    ) throws Exception {
        if (tagNames.isEmpty()) return;

        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        TagMetadataSyncer syncer = new TagMetadataSyncer(lfClient, "123456789012");

        when(lfClient.listLFTagKeys("123456789012"))
                .thenReturn(new ArrayList<>(tagNames));
        when(lfClient.getResourceLFTags(any(), any()))
                .thenThrow(new RuntimeException("LF timeout"));

        // Build desired with one resource so getResourceLFTags is called
        ServiceTags desired = buildServiceTagsWithResource(tagNames);
        TagSyncResult result = syncer.sync(desired, tagNames);

        assertFalse(result.isSuccess());
        verify(lfClient, never()).addLFTagsToResource(any(), any(), any());
        verify(lfClient, never()).removeLFTagsFromResource(any(), any(), any());
        verify(lfClient, never()).deleteLFTag(any(), any());
    }

    // -----------------------------------------------------------------------
    // Property 8: Tag rename — old in managed+actual, new in desired → delete+create
    // -----------------------------------------------------------------------
    @Property(tries = 30)
    void tagRename_deleteOldCreateNew(
            @ForAll("tagNamePairs") String[] namePair
    ) throws Exception {
        String oldName = namePair[0];
        String newName = namePair[1];

        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        TagMetadataSyncer syncer = new TagMetadataSyncer(lfClient, "123456789012");

        when(lfClient.listLFTagKeys("123456789012")).thenReturn(List.of(oldName));

        // Desired: new name only
        ServiceTags desired = buildServiceTags(Set.of(newName));
        // lfManagedTags includes old name (was managed before rename)
        TagSyncResult result = syncer.sync(desired, Set.of(oldName));

        assertTrue(result.isSuccess());
        verify(lfClient).createLFTag("123456789012", newName, List.of("true"));
        verify(lfClient).deleteLFTag("123456789012", oldName);
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Set<String>> tagNameSets() {
        Arbitrary<String> names = Arbitraries.of("PII", "SENSITIVE", "PUBLIC", "CONFIDENTIAL", "RESTRICTED");
        return names.set().ofMinSize(0).ofMaxSize(4);
    }

    @Provide
    Arbitrary<String[]> tagNamePairs() {
        return Arbitraries.of(
                new String[]{"OLD_PII", "PII"},
                new String[]{"SENSITIVE_V1", "SENSITIVE_V2"},
                new String[]{"DATA_CLASS_A", "DATA_CLASS_B"},
                new String[]{"LEGACY_TAG", "NEW_TAG"}
        );
    }

    // --- helpers ---

    private ServiceTags buildServiceTags(Set<String> tagNames) {
        Map<Long, RangerTagDef> defs = new HashMap<>();
        long id = 1L;
        for (String name : tagNames) {
            RangerTagDef def = new RangerTagDef(name);
            def.setId(id++);
            defs.put(def.getId(), def);
        }
        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(1L);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(defs);
        tags.setTags(new HashMap<>());
        tags.setServiceResources(List.of());
        tags.setResourceToTagIds(new HashMap<>());
        return tags;
    }

    private ServiceTags buildServiceTagsWithResource(Set<String> tagNames) {
        // Build desired with one resource AND a tag assigned to it so getResourceLFTags is invoked
        org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource dbRes =
                new org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource("testdb");
        Map<String, org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource> elements =
                new HashMap<>(Map.of("database", dbRes));
        org.apache.ranger.plugin.model.RangerServiceResource resource =
                new org.apache.ranger.plugin.model.RangerServiceResource();
        resource.setResourceElements(elements);
        resource.setId(100L);

        Map<Long, RangerTagDef> defs = new HashMap<>();
        org.apache.ranger.plugin.model.RangerTag tag =
                new org.apache.ranger.plugin.model.RangerTag();
        tag.setId(10L);

        long id = 1L;
        String firstTagName = null;
        for (String name : tagNames) {
            RangerTagDef def = new RangerTagDef(name);
            def.setId(id++);
            defs.put(def.getId(), def);
            if (firstTagName == null) firstTagName = name;
        }

        Map<Long, org.apache.ranger.plugin.model.RangerTag> tagMap = new HashMap<>();
        Map<Long, List<Long>> resToTagIds = new HashMap<>();
        if (firstTagName != null) {
            tag.setType(firstTagName);
            tagMap.put(10L, tag);
            resToTagIds.put(100L, List.of(10L));
        }

        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(1L);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(defs);
        tags.setTags(tagMap);
        tags.setServiceResources(List.of(resource));
        tags.setResourceToTagIds(resToTagIds);
        return tags;
    }
}
