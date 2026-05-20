package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.config.TagSyncConfig;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.TagMetadataSyncer;
import com.amazonaws.policyconverters.model.TagSyncResult;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.ranger.service.RangerTagService;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.util.ServiceTags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncServiceTagSyncTest {

    @Mock private RangerToCedarConverter rangerToCedarConverter;
    @Mock private CedarToLFConverter cedarToLFConverter;
    @Mock private LakeFormationClient lakeFormationClient;
    @Mock private DeadLetterLogger deadLetterLogger;
    @Mock private RangerTagService rangerTagService;
    @Mock private TagMetadataSyncer tagMetadataSyncer;

    private SyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new SyncService(
                new RangerPlugin(), rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, new GapReporter(), deadLetterLogger);
    }

    // --- disabled → null ---

    @Test
    void tagSyncDisabled_returnsNull() {
        // No setTagSync called → rangerTagService/tagMetadataSyncer are null
        SyncConfig config = makeConfig(false, 0L);
        syncService.start(config);

        assertNull(syncService.executeTagMetadataSync());
    }

    @Test
    void tagSyncEnabled_butComponentsNotSet_returnsNull() {
        // setTagSync not called
        SyncConfig config = makeConfig(true, 0L);
        syncService.start(config);

        assertNull(syncService.executeTagMetadataSync());
    }

    @Test
    void tagSyncEnabled_withComponents_butConfigSaysDisabled_returnsNull() {
        syncService.setTagSync(rangerTagService, tagMetadataSyncer);
        SyncConfig config = makeConfig(false, 0L);
        syncService.start(config);

        assertNull(syncService.executeTagMetadataSync());
        verifyNoInteractions(rangerTagService, tagMetadataSyncer);
    }

    // --- interval not elapsed → skip ---

    @Test
    void intervalNotElapsed_returnsNull() {
        syncService.setTagSync(rangerTagService, tagMetadataSyncer);
        SyncConfig config = makeConfig(true, 60_000L); // 60s interval
        syncService.start(config);

        // First call runs
        ServiceTags tags = makeServiceTags();
        when(rangerTagService.getLatestTags()).thenReturn(tags);
        when(tagMetadataSyncer.sync(any(), any()))
                .thenReturn(TagSyncResult.success(10, 0, 0, 0, 0, 0));
        syncService.executeTagMetadataSync();

        // Second call immediately — interval not elapsed
        assertNull(syncService.executeTagMetadataSync());
        verify(rangerTagService, times(1)).getLatestTags();
    }

    // --- getLatestTags returns null → failure result ---

    @Test
    void getLatestTags_returnsNull_producesFailureResult() {
        syncService.setTagSync(rangerTagService, tagMetadataSyncer);
        SyncConfig config = makeConfig(true, 0L);
        syncService.start(config);

        when(rangerTagService.getLatestTags()).thenReturn(null);

        TagSyncResult result = syncService.executeTagMetadataSync();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        verifyNoInteractions(tagMetadataSyncer);
    }

    // --- successful sync → checkpoint updated ---

    @Test
    void successfulSync_checkpointSaved(@TempDir Path tempDir) {
        Path checkpointPath = tempDir.resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(checkpointPath, new ObjectMapper());

        SyncService svc = new SyncService(
                new RangerPlugin(), rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, new GapReporter(), deadLetterLogger, store);
        svc.setTagSync(rangerTagService, tagMetadataSyncer);
        svc.start(makeConfig(true, 0L));

        ServiceTags tags = makeServiceTagsWithName("PII", 5L);
        when(rangerTagService.getLatestTags()).thenReturn(tags);
        when(rangerTagService.getLastKnownTagVersion()).thenReturn(5L);
        when(tagMetadataSyncer.sync(eq(tags), any()))
                .thenReturn(TagSyncResult.success(100, 1, 0, 0, 0, 0));

        TagSyncResult result = svc.executeTagMetadataSync();

        assertTrue(result.isSuccess());
        // Checkpoint should have been saved with tag state
        assertTrue(store.load().isPresent());
        assertEquals(5L, store.load().get().getLastKnownTagVersion());
        assertTrue(store.load().get().getLastKnownRangerTagNames().contains("PII"));
    }

    // --- tag sync failure doesn't affect policy sync result ---

    @Test
    void tagSyncFailure_doesNotPropagateException() {
        syncService.setTagSync(rangerTagService, tagMetadataSyncer);
        SyncConfig config = makeConfig(true, 0L);
        syncService.start(config);

        when(rangerTagService.getLatestTags()).thenReturn(makeServiceTags());
        when(tagMetadataSyncer.sync(any(), any()))
                .thenReturn(TagSyncResult.failure(10, new RuntimeException("LF down")));

        TagSyncResult result = syncService.executeTagMetadataSync();
        assertFalse(result.isSuccess());
        // No exception thrown — failure is returned, not propagated
    }

    // --- interval = 0 → uses policyRefreshIntervalMs (but for test: always runs) ---

    @Test
    void zeroTagSyncInterval_usesDefaultBehavior() {
        syncService.setTagSync(rangerTagService, tagMetadataSyncer);
        // interval=0 → resolveTagSyncInterval returns policyRefreshIntervalMs (30s)
        // but lastTagSyncMs is 0 and (now - 0) > 30_000 for any real time
        SyncConfig config = makeConfig(true, 0L);
        syncService.start(config);

        ServiceTags tags = makeServiceTags();
        when(rangerTagService.getLatestTags()).thenReturn(tags);
        when(tagMetadataSyncer.sync(any(), any()))
                .thenReturn(TagSyncResult.success(5, 0, 0, 0, 0, 0));

        TagSyncResult result = syncService.executeTagMetadataSync();
        assertNotNull(result);
        assertTrue(result.isSuccess());
    }

    // --- helpers ---

    private SyncConfig makeConfig(boolean tagSyncEnabled, long tagSyncIntervalMs) {
        TagSyncConfig tagSyncConfig = new TagSyncConfig(tagSyncEnabled,
                tagSyncEnabled ? "tagservice" : null, tagSyncIntervalMs);
        return new SyncConfig(null, null, null, 30000L, 5, 2000L, null,
                null, 0, null, tagSyncConfig);
    }

    private ServiceTags makeServiceTags() {
        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(1L);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(new HashMap<>());
        tags.setTags(new HashMap<>());
        tags.setServiceResources(List.of());
        tags.setResourceToTagIds(new HashMap<>());
        return tags;
    }

    private ServiceTags makeServiceTagsWithName(String tagName, long version) {
        RangerTagDef def = new RangerTagDef(tagName);
        def.setId(1L);
        ServiceTags tags = new ServiceTags();
        tags.setTagVersion(version);
        tags.setOp(ServiceTags.OP_ADD_OR_UPDATE);
        tags.setTagDefinitions(new HashMap<>(Map.of(1L, def)));
        tags.setTags(new HashMap<>());
        tags.setServiceResources(List.of());
        tags.setResourceToTagIds(new HashMap<>());
        return tags;
    }
}
