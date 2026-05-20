package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.model.TagSyncResult;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.util.ServiceTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles Ranger tag definitions and resource-tag mappings into LF-Tags
 * and LF resource tag attachments via a 3-way diff (desired vs. actual).
 *
 * Only manages tags tracked in {@code lfManagedTags} — never touches keys
 * created outside this pipeline.
 */
public class TagMetadataSyncer {

    private static final Logger LOG = LoggerFactory.getLogger(TagMetadataSyncer.class);

    private static final String TAG_VALUE = "true";

    private final LakeFormationClient lfClient;
    private final String catalogId;

    public TagMetadataSyncer(LakeFormationClient lfClient, String catalogId) {
        this.lfClient = lfClient;
        this.catalogId = catalogId;
    }

    /**
     * Execute one reconciliation cycle.
     *
     * @param desired       ServiceTags from RangerTagService (desired state)
     * @param lfManagedTags set of tag names last known to be managed by this pipeline
     * @return TagSyncResult with operation counts and any failures
     */
    public TagSyncResult sync(ServiceTags desired, Set<String> lfManagedTags) {
        long start = System.currentTimeMillis();
        int tagsCreated = 0, tagsDeleted = 0, attachmentsAdded = 0, attachmentsRemoved = 0, failed = 0;

        // Step 1: build desired tag names from Ranger tag definitions
        Set<String> desiredTagNames = buildDesiredTagNames(desired);

        LOG.info("TagMetadataSyncer: starting sync — desired tags={}, managed tags={}",
                desiredTagNames.size(), lfManagedTags.size());

        // Step 2: fetch actual tag keys from LF
        List<String> actualTagKeyList;
        try {
            actualTagKeyList = lfClient.listLFTagKeys(catalogId);
        } catch (Exception e) {
            LOG.error("TagMetadataSyncer: failed to list LF-Tags, aborting: {}", e.getMessage(), e);
            return TagSyncResult.failure(System.currentTimeMillis() - start, e);
        }
        Set<String> actualTagNames = new HashSet<>(actualTagKeyList);

        // Step 3: tag definition diff
        Set<String> toCreate = new HashSet<>(desiredTagNames);
        toCreate.removeAll(actualTagNames);

        Set<String> toDelete = new HashSet<>(lfManagedTags);
        toDelete.retainAll(actualTagNames);
        toDelete.removeAll(desiredTagNames);

        // Step 4: apply tag definition creates
        for (String tagName : toCreate) {
            try {
                lfClient.createLFTag(catalogId, tagName, List.of(TAG_VALUE));
                tagsCreated++;
            } catch (Exception e) {
                LOG.error("TagMetadataSyncer: failed to create LF-Tag key={}: {}", tagName, e.getMessage(), e);
                failed++;
            }
        }

        // Step 5: build desired resource-tag attachments from ServiceTags
        Map<LFResource, Set<String>> desiredAttachments = buildDesiredAttachments(desired);

        // Step 6: build actual attachments from LF — abort if any resource fails
        Map<LFResource, Map<String, String>> actualAttachments;
        try {
            actualAttachments = buildActualAttachments(desiredAttachments.keySet());
        } catch (Exception e) {
            LOG.error("TagMetadataSyncer: failed to read LF resource tags, aborting: {}", e.getMessage(), e);
            return TagSyncResult.failure(System.currentTimeMillis() - start, e);
        }

        // Step 7: apply attachment adds
        for (Map.Entry<LFResource, Set<String>> entry : desiredAttachments.entrySet()) {
            LFResource resource = entry.getKey();
            Set<String> desiredTags = entry.getValue();
            Map<String, String> actual = actualAttachments.getOrDefault(resource, Collections.emptyMap());

            Map<String, String> toAdd = new HashMap<>();
            for (String tagName : desiredTags) {
                if (!actual.containsKey(tagName)) {
                    toAdd.put(tagName, TAG_VALUE);
                }
            }
            if (!toAdd.isEmpty()) {
                try {
                    lfClient.addLFTagsToResource(resource, toAdd, catalogId);
                    attachmentsAdded += toAdd.size();
                } catch (Exception e) {
                    LOG.error("TagMetadataSyncer: failed to add LF-Tags to resource={}, tags={}: {}",
                            resource, toAdd.keySet(), e.getMessage(), e);
                    failed += toAdd.size();
                }
            }
        }

        // Step 8: apply attachment removes (only for tags we own)
        for (Map.Entry<LFResource, Map<String, String>> entry : actualAttachments.entrySet()) {
            LFResource resource = entry.getKey();
            Map<String, String> actual = entry.getValue();
            Set<String> desiredForResource = desiredAttachments.getOrDefault(resource, Collections.emptySet());

            List<String> toRemove = new ArrayList<>();
            for (String tagName : actual.keySet()) {
                if (lfManagedTags.contains(tagName) && !desiredForResource.contains(tagName)) {
                    toRemove.add(tagName);
                }
            }
            if (!toRemove.isEmpty()) {
                try {
                    lfClient.removeLFTagsFromResource(resource, toRemove, catalogId);
                    attachmentsRemoved += toRemove.size();
                } catch (Exception e) {
                    LOG.error("TagMetadataSyncer: failed to remove LF-Tags from resource={}, tags={}: {}",
                            resource, toRemove, e.getMessage(), e);
                    failed += toRemove.size();
                }
            }
        }

        // Step 9: apply tag definition deletes (skip if resource still has attachments)
        for (String tagName : toDelete) {
            // Check whether any resource still carries this tag in the desired or actual state
            boolean stillAttached = false;
            for (Map<String, String> actual : actualAttachments.values()) {
                if (actual.containsKey(tagName)) {
                    stillAttached = true;
                    break;
                }
            }
            if (stillAttached) {
                LOG.info("TagMetadataSyncer: deferring deletion of LF-Tag key={} — still has resource attachments",
                        tagName);
                continue;
            }
            try {
                lfClient.deleteLFTag(catalogId, tagName);
                tagsDeleted++;
            } catch (Exception e) {
                LOG.error("TagMetadataSyncer: failed to delete LF-Tag key={}: {}", tagName, e.getMessage(), e);
                failed++;
            }
        }

        long duration = System.currentTimeMillis() - start;
        TagSyncResult result = TagSyncResult.success(duration, tagsCreated, tagsDeleted,
                attachmentsAdded, attachmentsRemoved, failed);

        if (failed > 0) {
            LOG.warn("TagMetadataSyncer: cycle completed with {} failure(s): {}", failed, result);
        } else {
            LOG.info("TagMetadataSyncer: cycle completed: {}", result);
        }
        return result;
    }

    private Set<String> buildDesiredTagNames(ServiceTags desired) {
        if (desired == null || desired.getTagDefinitions() == null) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (RangerTagDef def : desired.getTagDefinitions().values()) {
            if (def.getName() != null) {
                names.add(def.getName());
            }
        }
        return names;
    }

    private Map<LFResource, Set<String>> buildDesiredAttachments(ServiceTags desired) {
        if (desired == null) return Collections.emptyMap();

        Map<Long, RangerTagDef> tagDefs = desired.getTagDefinitions();
        Map<Long, RangerTag> tagInstances = desired.getTags();
        Map<Long, List<Long>> resourceToTagIds = desired.getResourceToTagIds();
        List<RangerServiceResource> serviceResources = desired.getServiceResources();

        if (tagDefs == null || tagInstances == null || resourceToTagIds == null || serviceResources == null) {
            return Collections.emptyMap();
        }

        // RangerTag.type matches RangerTagDef.name
        Map<String, String> typeToName = new HashMap<>();
        for (RangerTagDef def : tagDefs.values()) {
            if (def.getName() != null) typeToName.put(def.getName(), def.getName());
        }

        Map<LFResource, Set<String>> result = new HashMap<>();
        for (RangerServiceResource resource : serviceResources) {
            if (resource.getId() == null) continue;
            LFResource lfResource = mapToLFResource(resource);
            if (lfResource == null) continue;

            List<Long> tagIds = resourceToTagIds.getOrDefault(resource.getId(), Collections.emptyList());
            Set<String> tagNames = new HashSet<>();
            for (Long tagId : tagIds) {
                RangerTag tag = tagInstances.get(tagId);
                if (tag == null) continue;
                String name = typeToName.get(tag.getType());
                if (name != null) tagNames.add(name);
            }
            if (!tagNames.isEmpty()) {
                result.put(lfResource, tagNames);
            }
        }
        return result;
    }

    private Map<LFResource, Map<String, String>> buildActualAttachments(Set<LFResource> resources) {
        Map<LFResource, Map<String, String>> result = new HashMap<>();
        for (LFResource resource : resources) {
            // Throws on failure — caller aborts reconciliation
            Map<String, String> tags = lfClient.getResourceLFTags(resource, catalogId);
            result.put(resource, tags);
        }
        return result;
    }

    static LFResource mapToLFResource(RangerServiceResource resource) {
        Map<String, RangerPolicyResource> elements = resource.getResourceElements();
        if (elements == null || elements.isEmpty()) return null;

        String databaseName = getFirstValue(elements, "database");
        String tableName = getFirstValue(elements, "table");
        String columnName = getFirstValue(elements, "column");

        if (databaseName == null) return null;

        Set<String> columns = columnName != null ? Set.of(columnName) : null;
        return new LFResource(null, databaseName, tableName, columns, null, null);
    }

    private static String getFirstValue(Map<String, RangerPolicyResource> elements, String key) {
        RangerPolicyResource r = elements.get(key);
        if (r == null || r.getValues() == null || r.getValues().isEmpty()) return null;
        return r.getValues().get(0);
    }
}
