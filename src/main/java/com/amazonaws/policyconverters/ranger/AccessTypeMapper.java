package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
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
 *   <li>update → {INSERT}</li>
 *   <li>create → {CREATE_TABLE}</li>
 *   <li>drop → {DROP}</li>
 *   <li>alter → {ALTER}</li>
 *   <li>read → {SELECT}</li>
 *   <li>write → {INSERT}</li>
 *   <li>all → {SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE}</li>
 *   <li>datalocation → {DATA_LOCATION_ACCESS}</li>
 *   <li>data_location_access → {DATA_LOCATION_ACCESS}</li>
 * </ul>
 */
public final class AccessTypeMapper {

    private static final Logger LOG = LoggerFactory.getLogger(AccessTypeMapper.class);

    private static final Map<String, Set<LFPermission>> MAPPING;

    static {
        Map<String, Set<LFPermission>> m = new HashMap<>();
        m.put("select", Collections.unmodifiableSet(EnumSet.of(LFPermission.SELECT)));
        m.put("update", Collections.unmodifiableSet(EnumSet.of(LFPermission.INSERT)));
        m.put("create", Collections.unmodifiableSet(EnumSet.of(LFPermission.CREATE_TABLE)));
        m.put("drop", Collections.unmodifiableSet(EnumSet.of(LFPermission.DROP)));
        m.put("alter", Collections.unmodifiableSet(EnumSet.of(LFPermission.ALTER)));
        m.put("read", Collections.unmodifiableSet(EnumSet.of(LFPermission.SELECT)));
        m.put("write", Collections.unmodifiableSet(EnumSet.of(LFPermission.INSERT)));
        m.put("all", Collections.unmodifiableSet(EnumSet.of(
                LFPermission.SELECT, LFPermission.INSERT, LFPermission.DELETE,
                LFPermission.ALTER, LFPermission.DROP, LFPermission.DESCRIBE)));
        m.put("datalocation", Collections.unmodifiableSet(EnumSet.of(LFPermission.DATA_LOCATION_ACCESS)));
        m.put("data_location_access", Collections.unmodifiableSet(EnumSet.of(LFPermission.DATA_LOCATION_ACCESS)));
        MAPPING = Collections.unmodifiableMap(m);
    }

    private AccessTypeMapper() {
        // utility class
    }

    /**
     * Map a single Ranger access type to the corresponding set of Lake Formation permissions.
     *
     * @param rangerAccessType the Ranger access type (case-insensitive)
     * @return the set of LF permissions, or an empty set if the access type is unknown
     */
    public static Set<LFPermission> mapAccessType(String rangerAccessType) {
        if (rangerAccessType == null || rangerAccessType.trim().isEmpty()) {
            LOG.warn("Null or empty Ranger access type provided");
            return Collections.emptySet();
        }
        String normalized = rangerAccessType.trim().toLowerCase();
        Set<LFPermission> result = MAPPING.get(normalized);
        if (result == null) {
            LOG.warn("Unknown Ranger access type: '{}'", rangerAccessType);
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
