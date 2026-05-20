package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.admin.client.RangerAdminRESTClient;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.util.ServiceTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieves Ranger tag definitions and resource-tag mappings via
 * RangerAdminRESTClient. Supports incremental fetches and falls back
 * to the last known good state on retrieval failure.
 */
public class RangerTagService {

    private static final Logger LOG = LoggerFactory.getLogger(RangerTagService.class);

    private final RangerAdminRESTClient adminClient;
    private final String tagServiceName;

    private volatile ServiceTags lastKnownTags;
    private volatile long lastKnownTagVersion = 0L;

    public RangerTagService(String tagServiceName, RangerConnectionConfig rangerConfig) {
        this.tagServiceName = tagServiceName;
        this.adminClient = createAdminClient(tagServiceName, rangerConfig);
    }

    RangerTagService(String tagServiceName, RangerAdminRESTClient adminClient) {
        this.tagServiceName = tagServiceName;
        this.adminClient = adminClient;
    }

    /**
     * Retrieve latest tags using incremental fetch when a prior version is known.
     * On failure, returns the last known good state (may be null on first call after fresh start).
     */
    public ServiceTags getLatestTags() {
        try {
            ServiceTags response = adminClient.getServiceTagsIfUpdated(lastKnownTagVersion, 0L);
            if (response == null) {
                LOG.debug("RangerTagService: no change (null response), tagServiceName={}, version={}",
                        tagServiceName, lastKnownTagVersion);
                return lastKnownTags;
            }

            if (Boolean.TRUE.equals(response.getIsDelta())) {
                lastKnownTags = mergeDelta(lastKnownTags, response);
                LOG.info("RangerTagService: merged delta, tagServiceName={}, newVersion={}",
                        tagServiceName, response.getTagVersion());
            } else {
                lastKnownTags = response;
                LOG.info("RangerTagService: full replace, tagServiceName={}, newVersion={}",
                        tagServiceName, response.getTagVersion());
            }

            if (response.getTagVersion() != null) {
                lastKnownTagVersion = response.getTagVersion();
            }

            return lastKnownTags;
        } catch (Exception e) {
            LOG.error("RangerTagService: failed to fetch tags from tagServiceName={}: {}",
                    tagServiceName, e.getMessage(), e);
            return lastKnownTags;
        }
    }

    public long getLastKnownTagVersion() {
        return lastKnownTagVersion;
    }

    /**
     * Build a map of tag name → set of LFResources that carry that tag.
     * Used by Phase 2 named-resource fallback to expand a tag to concrete Glue resources.
     */
    public Map<String, Set<LFResource>> getResourcesForTag() {
        ServiceTags tags = lastKnownTags;
        if (tags == null) {
            return Collections.emptyMap();
        }

        Map<Long, RangerTagDef> tagDefs = tags.getTagDefinitions();
        Map<Long, RangerTag> tagInstances = tags.getTags();
        Map<Long, List<Long>> resourceToTagIds = tags.getResourceToTagIds();
        List<RangerServiceResource> serviceResources = tags.getServiceResources();

        if (tagDefs == null || tagInstances == null || resourceToTagIds == null || serviceResources == null) {
            return Collections.emptyMap();
        }

        // Build type-id → tag name lookup via tag instances
        Map<String, String> typeToName = buildTypeToName(tagDefs, tagInstances);

        Map<String, Set<LFResource>> result = new HashMap<>();
        for (RangerServiceResource resource : serviceResources) {
            if (resource.getId() == null) continue;
            List<Long> tagIds = resourceToTagIds.getOrDefault(resource.getId(), Collections.emptyList());
            LFResource lfResource = mapToLFResource(resource);
            if (lfResource == null) continue;

            for (Long tagId : tagIds) {
                RangerTag tag = tagInstances.get(tagId);
                if (tag == null) continue;
                String tagName = typeToName.get(tag.getType());
                if (tagName == null) continue;
                result.computeIfAbsent(tagName, k -> new HashSet<>()).add(lfResource);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private ServiceTags mergeDelta(ServiceTags base, ServiceTags delta) {
        if (base == null) {
            return delta;
        }

        // Merge tag definitions
        Map<Long, RangerTagDef> mergedDefs = new HashMap<>();
        if (base.getTagDefinitions() != null) mergedDefs.putAll(base.getTagDefinitions());
        if (delta.getTagDefinitions() != null) {
            if (ServiceTags.OP_DELETE.equals(delta.getOp())) {
                delta.getTagDefinitions().keySet().forEach(mergedDefs::remove);
            } else {
                mergedDefs.putAll(delta.getTagDefinitions());
            }
        }

        // Merge tag instances
        Map<Long, RangerTag> mergedTags = new HashMap<>();
        if (base.getTags() != null) mergedTags.putAll(base.getTags());
        if (delta.getTags() != null) {
            if (ServiceTags.OP_DELETE.equals(delta.getOp())) {
                delta.getTags().keySet().forEach(mergedTags::remove);
            } else {
                mergedTags.putAll(delta.getTags());
            }
        }

        // Merge service resources
        Map<Long, RangerServiceResource> mergedResources = new HashMap<>();
        if (base.getServiceResources() != null) {
            for (RangerServiceResource r : base.getServiceResources()) {
                if (r.getId() != null) mergedResources.put(r.getId(), r);
            }
        }
        if (delta.getServiceResources() != null) {
            for (RangerServiceResource r : delta.getServiceResources()) {
                if (r.getId() == null) continue;
                if (ServiceTags.OP_DELETE.equals(delta.getOp())) {
                    mergedResources.remove(r.getId());
                } else {
                    mergedResources.put(r.getId(), r);
                }
            }
        }

        // Merge resource-to-tag-ids
        Map<Long, List<Long>> mergedResToTagIds = new HashMap<>();
        if (base.getResourceToTagIds() != null) mergedResToTagIds.putAll(base.getResourceToTagIds());
        if (delta.getResourceToTagIds() != null) {
            if (ServiceTags.OP_DELETE.equals(delta.getOp())) {
                delta.getResourceToTagIds().keySet().forEach(mergedResToTagIds::remove);
            } else {
                mergedResToTagIds.putAll(delta.getResourceToTagIds());
            }
        }

        return new ServiceTags(
                delta.getOp(),
                delta.getServiceName(),
                delta.getTagVersion(),
                delta.getTagUpdateTime(),
                mergedDefs,
                mergedTags,
                List.copyOf(mergedResources.values()),
                mergedResToTagIds);
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

    private static Map<String, String> buildTypeToName(Map<Long, RangerTagDef> tagDefs,
                                                        Map<Long, RangerTag> tagInstances) {
        // RangerTag.type is the type string; find the corresponding RangerTagDef by name match
        // tagDefs are keyed by their own id; the tag's type field matches RangerTagDef.getName()
        Map<String, String> typeToName = new HashMap<>();
        for (RangerTagDef def : tagDefs.values()) {
            if (def.getName() != null) {
                typeToName.put(def.getName(), def.getName());
            }
        }
        return typeToName;
    }

    private static RangerAdminRESTClient createAdminClient(String tagServiceName,
                                                            RangerConnectionConfig rangerConfig) {
        Configuration conf = new Configuration(false);
        conf.set("ranger.plugin." + tagServiceName + ".policy.rest.url",
                rangerConfig.getRangerAdminUrl());

        if (rangerConfig.getUsername() != null && rangerConfig.getPassword() != null) {
            conf.set("ranger.plugin." + tagServiceName + ".policy.rest.user",
                    rangerConfig.getUsername());
            conf.set("ranger.plugin." + tagServiceName + ".policy.rest.password",
                    rangerConfig.getPassword());
        }

        if (rangerConfig.getKerberosKeytab() != null) {
            conf.set("ranger.plugin." + tagServiceName + ".policy.rest.ssl.config.file", "");
        }

        RangerAdminRESTClient client = new RangerAdminRESTClient();
        client.init(tagServiceName, "ranger-lf-tag-sync", "", conf);
        return client;
    }
}
