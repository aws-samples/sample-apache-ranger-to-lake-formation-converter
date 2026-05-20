package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.lakeformation.LFResource;
import org.apache.ranger.admin.client.RangerAdminRESTClient;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.util.ServiceTags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RangerTagServiceTest {

    @Mock
    private RangerAdminRESTClient adminClient;

    private RangerTagService tagService;

    @BeforeEach
    void setUp() {
        tagService = new RangerTagService("tagservice", adminClient);
    }

    // --- getLatestTags: first call uses version 0 ---

    @Test
    void firstCall_usesVersionZero() throws Exception {
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(makeFullServiceTags(1L, false));
        tagService.getLatestTags();
        verify(adminClient).getServiceTagsIfUpdated(0L, 0L);
    }

    @Test
    void firstCall_setsLastKnownVersion() throws Exception {
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(makeFullServiceTags(5L, false));
        tagService.getLatestTags();
        assertEquals(5L, tagService.getLastKnownTagVersion());
    }

    // --- null response → return last known state ---

    @Test
    void nullResponse_returnsLastKnownTags() throws Exception {
        ServiceTags first = makeFullServiceTags(1L, false);
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(first);
        tagService.getLatestTags();

        when(adminClient.getServiceTagsIfUpdated(1L, 0L)).thenReturn(null);
        ServiceTags result = tagService.getLatestTags();
        assertSame(first, result);
    }

    @Test
    void nullResponse_doesNotUpdateVersion() throws Exception {
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(makeFullServiceTags(3L, false));
        tagService.getLatestTags();

        when(adminClient.getServiceTagsIfUpdated(3L, 0L)).thenReturn(null);
        tagService.getLatestTags();
        assertEquals(3L, tagService.getLastKnownTagVersion());
    }

    // --- exception → return last known state ---

    @Test
    void exception_returnsLastKnownTags() throws Exception {
        ServiceTags first = makeFullServiceTags(2L, false);
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(first);
        tagService.getLatestTags();

        when(adminClient.getServiceTagsIfUpdated(2L, 0L)).thenThrow(new RuntimeException("timeout"));
        ServiceTags result = tagService.getLatestTags();
        assertSame(first, result);
    }

    @Test
    void exception_onFirstCall_returnsNull() throws Exception {
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenThrow(new RuntimeException("timeout"));
        assertNull(tagService.getLatestTags());
    }

    // --- isDelta=true → merge ---

    @Test
    void deltaResponse_mergesWithLastKnown() throws Exception {
        ServiceTags base = makeFullServiceTags(1L, false);
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(base);
        tagService.getLatestTags();

        // Delta adds a new tag def
        ServiceTags delta = new ServiceTags();
        delta.setIsDelta(true);
        delta.setTagVersion(2L);
        delta.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        RangerTagDef newDef = new RangerTagDef("SENSITIVE");
        newDef.setId(99L);
        delta.setTagDefinitions(Map.of(99L, newDef));
        delta.setTags(new HashMap<>());
        delta.setServiceResources(List.of());
        delta.setResourceToTagIds(new HashMap<>());

        when(adminClient.getServiceTagsIfUpdated(1L, 0L)).thenReturn(delta);
        ServiceTags merged = tagService.getLatestTags();

        assertNotNull(merged);
        // Should contain both original PII and newly merged SENSITIVE
        assertTrue(merged.getTagDefinitions().values().stream()
                .anyMatch(d -> "PII".equals(d.getName())));
        assertTrue(merged.getTagDefinitions().values().stream()
                .anyMatch(d -> "SENSITIVE".equals(d.getName())));
        assertEquals(2L, tagService.getLastKnownTagVersion());
    }

    // --- REPLACE / full response → replaces entirely ---

    @Test
    void fullReplaceResponse_replacesPreviousState() throws Exception {
        ServiceTags first = makeFullServiceTags(1L, false);
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(first);
        tagService.getLatestTags();

        // Second response is a full replace (isDelta=false)
        ServiceTags second = makeFullServiceTags(2L, false);
        RangerTagDef def = new RangerTagDef("NEWONLY");
        def.setId(77L);
        second.setTagDefinitions(Map.of(77L, def));
        when(adminClient.getServiceTagsIfUpdated(1L, 0L)).thenReturn(second);
        ServiceTags result = tagService.getLatestTags();

        assertSame(second, result);
        assertEquals(1, result.getTagDefinitions().size());
        assertEquals("NEWONLY", result.getTagDefinitions().get(77L).getName());
    }

    // --- getResourcesForTag ---

    @Test
    void getResourcesForTag_emptyWhenNoTagsLoaded() {
        assertTrue(tagService.getResourcesForTag().isEmpty());
    }

    @Test
    void getResourcesForTag_mapsTagNameToResources() throws Exception {
        ServiceTags tags = buildTagsWithResourceMapping();
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(tags);
        tagService.getLatestTags();

        Map<String, Set<LFResource>> result = tagService.getResourcesForTag();
        assertFalse(result.isEmpty());
        assertTrue(result.containsKey("PII"));
        Set<LFResource> resources = result.get("PII");
        assertEquals(1, resources.size());
        LFResource res = resources.iterator().next();
        assertEquals("testdb", res.getDatabaseName());
        assertEquals("testtable", res.getTableName());
    }

    @Test
    void getResourcesForTag_resultIsUnmodifiable() throws Exception {
        when(adminClient.getServiceTagsIfUpdated(0L, 0L)).thenReturn(makeFullServiceTags(1L, false));
        tagService.getLatestTags();
        assertThrows(UnsupportedOperationException.class,
                () -> tagService.getResourcesForTag().put("X", Set.of()));
    }

    // --- mapToLFResource static helper ---

    @Test
    void mapToLFResource_databaseOnly() {
        RangerServiceResource resource = makeResource("mydb", null, null);
        LFResource lf = RangerTagService.mapToLFResource(resource);
        assertNotNull(lf);
        assertEquals("mydb", lf.getDatabaseName());
        assertNull(lf.getTableName());
        assertNull(lf.getColumnNames());
    }

    @Test
    void mapToLFResource_databaseAndTable() {
        RangerServiceResource resource = makeResource("mydb", "mytable", null);
        LFResource lf = RangerTagService.mapToLFResource(resource);
        assertNotNull(lf);
        assertEquals("mydb", lf.getDatabaseName());
        assertEquals("mytable", lf.getTableName());
    }

    @Test
    void mapToLFResource_databaseTableAndColumn() {
        RangerServiceResource resource = makeResource("mydb", "mytable", "mycol");
        LFResource lf = RangerTagService.mapToLFResource(resource);
        assertNotNull(lf);
        assertEquals(Set.of("mycol"), lf.getColumnNames());
    }

    @Test
    void mapToLFResource_nullElements_returnsNull() {
        RangerServiceResource resource = new RangerServiceResource();
        assertNull(RangerTagService.mapToLFResource(resource));
    }

    @Test
    void mapToLFResource_missingDatabase_returnsNull() {
        RangerServiceResource resource = new RangerServiceResource();
        resource.setResourceElements(Map.of("table", new RangerPolicyResource("mytable")));
        assertNull(RangerTagService.mapToLFResource(resource));
    }

    // --- helpers ---

    private ServiceTags makeFullServiceTags(long version, boolean isDelta) {
        RangerTagDef def = new RangerTagDef("PII");
        def.setId(1L);

        RangerTag tag = new RangerTag("PII", null);
        tag.setId(10L);

        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(version);
        tags.setIsDelta(isDelta);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(new HashMap<>(Map.of(1L, def)));
        tags.setTags(new HashMap<>(Map.of(10L, tag)));
        tags.setServiceResources(List.of());
        tags.setResourceToTagIds(new HashMap<>());
        return tags;
    }

    private ServiceTags buildTagsWithResourceMapping() {
        RangerTagDef def = new RangerTagDef("PII");
        def.setId(1L);

        RangerTag tag = new RangerTag("PII", null);
        tag.setId(10L);

        RangerServiceResource resource = makeResource("testdb", "testtable", null);
        resource.setId(100L);

        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(1L);
        tags.setIsDelta(false);
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
