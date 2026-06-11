package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps Apache Ranger access types to AWS Lake Formation permissions.
 * <p>
 * Mapping table (case-insensitive):
 * <ul>
 *   <li>select → {SELECT}</li>
 *   <li>insert → {INSERT}</li>
 *   <li>delete → {DELETE}</li>
 *   <li>describe → {DESCRIBE}</li>
 *   <li>alter → {ALTER}</li>
 *   <li>drop → {DROP}</li>
 *   <li>create_database → {CREATE_DATABASE}</li>
 *   <li>create_table → {CREATE_TABLE}</li>
 *   <li>update → {INSERT} (legacy alias)</li>
 *   <li>create → {CREATE_TABLE} (legacy alias)</li>
 *   <li>read → {SELECT} (legacy alias)</li>
 *   <li>write → {INSERT} (legacy alias)</li>
 *   <li>all → {SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE}</li>
 *   <li>datalocation → {DATA_LOCATION_ACCESS}</li>
 *   <li>data_location_access → {DATA_LOCATION_ACCESS}</li>
 * </ul>
 */
public final class AccessTypeMapper {

    private static final Logger LOG = LoggerFactory.getLogger(AccessTypeMapper.class);

    private static volatile MetricsEmitter metricsEmitter;

    private static final Map<String, Set<LFPermission>> MAPPING;

    static {
        Map<String, Set<LFPermission>> m = new HashMap<>();
        m.put("select", Collections.unmodifiableSet(EnumSet.of(LFPermission.SELECT)));
        m.put("insert", Collections.unmodifiableSet(EnumSet.of(LFPermission.INSERT)));
        m.put("delete", Collections.unmodifiableSet(EnumSet.of(LFPermission.DELETE)));
        m.put("describe", Collections.unmodifiableSet(EnumSet.of(LFPermission.DESCRIBE)));
        m.put("alter", Collections.unmodifiableSet(EnumSet.of(LFPermission.ALTER)));
        m.put("drop", Collections.unmodifiableSet(EnumSet.of(LFPermission.DROP)));
        m.put("create_database", Collections.unmodifiableSet(EnumSet.of(LFPermission.CREATE_DATABASE)));
        m.put("create_table", Collections.unmodifiableSet(EnumSet.of(LFPermission.CREATE_TABLE)));

        // Legacy aliases from older Ranger service definitions
        m.put("update", Collections.unmodifiableSet(EnumSet.of(LFPermission.INSERT)));
        m.put("create", Collections.unmodifiableSet(EnumSet.of(LFPermission.CREATE_TABLE)));
        m.put("read", Collections.unmodifiableSet(EnumSet.of(LFPermission.SELECT)));
        m.put("write", Collections.unmodifiableSet(EnumSet.of(LFPermission.INSERT)));

        m.put("all", Collections.unmodifiableSet(EnumSet.of(LFPermission.ALL)));
        m.put("datalocation", Collections.unmodifiableSet(EnumSet.of(LFPermission.DATA_LOCATION_ACCESS)));
        m.put("data_location_access", Collections.unmodifiableSet(EnumSet.of(LFPermission.DATA_LOCATION_ACCESS)));
        MAPPING = Collections.unmodifiableMap(m);
    }

    private AccessTypeMapper() {
        // utility class
    }

    /**
     * Set the MetricsEmitter for publishing unmapped access type metrics to CloudWatch.
     * Should be called once during application startup.
     *
     * @param emitter the MetricsEmitter instance
     */
    public static void setMetricsEmitter(MetricsEmitter emitter) {
        metricsEmitter = emitter;
    }

    /**
     * Map a single Ranger access type to the corresponding set of Lake Formation permissions.
     *
     * @param rangerAccessType the Ranger access type (case-insensitive)
     * @return the set of LF permissions, or an empty set if the access type is unknown
     */
    public static Set<LFPermission> mapAccessType(String rangerAccessType) {
        if (rangerAccessType == null || rangerAccessType.trim().isEmpty()) {
            LOG.error("Null or empty Ranger access type provided");
            return Collections.emptySet();
        }
        String normalized = rangerAccessType.trim().toLowerCase();
        Set<LFPermission> result = MAPPING.get(normalized);
        if (result == null) {
            LOG.error("Unknown Ranger access type: '{}' — this access type will be skipped, "
                    + "affected policy items may lose permissions", rangerAccessType);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedAccessType(rangerAccessType);
            }
            return Collections.emptySet();
        }
        return result;
    }

    /**
     * Map multiple Ranger access types and return the union of all corresponding LF permissions.
     *
     * @param rangerAccessTypes collection of Ranger access types
     * @return the union of all mapped LF permissions
     */
    public static Set<LFPermission> mapAccessTypes(Collection<String> rangerAccessTypes) {
        if (rangerAccessTypes == null || rangerAccessTypes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<LFPermission> result = EnumSet.noneOf(LFPermission.class);
        for (String accessType : rangerAccessTypes) {
            result.addAll(mapAccessType(accessType));
        }
        return Collections.unmodifiableSet(result);
    }
}
