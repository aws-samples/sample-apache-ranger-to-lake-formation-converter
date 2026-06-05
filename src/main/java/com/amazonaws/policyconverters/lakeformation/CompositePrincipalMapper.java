package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Chains multiple {@link PrincipalMapper} delegates in order, returning the first
 * non-empty resolution. Only emits the unmapped-principal metric after all delegates
 * are exhausted. Delegates should be constructed with a null MetricsEmitter to avoid
 * double-counting intermediate misses; {@link PrincipalMapperFactory} handles this
 * when building composite instances.
 */
public class CompositePrincipalMapper implements PrincipalMapper {

    private static final Logger LOG = LoggerFactory.getLogger(CompositePrincipalMapper.class);

    private final List<PrincipalMapper> delegates;
    private final MetricsEmitter metricsEmitter;  // nullable

    public CompositePrincipalMapper(List<PrincipalMapper> delegates, MetricsEmitter metricsEmitter) {
        if (delegates == null) throw new IllegalArgumentException("delegates must not be null");
        this.delegates = Collections.unmodifiableList(delegates);
        this.metricsEmitter = metricsEmitter;
    }

    @Override
    public Optional<String> resolveUser(String rangerUser) {
        return resolve("user", rangerUser, d -> d.resolveUser(rangerUser));
    }

    @Override
    public Optional<String> resolveGroup(String rangerGroup) {
        return resolve("group", rangerGroup, d -> d.resolveGroup(rangerGroup));
    }

    @Override
    public Optional<String> resolveRole(String rangerRole) {
        return resolve("role", rangerRole, d -> d.resolveRole(rangerRole));
    }

    private Optional<String> resolve(String principalType, String name,
                                     java.util.function.Function<PrincipalMapper, Optional<String>> fn) {
        for (PrincipalMapper delegate : delegates) {
            Optional<String> result = fn.apply(delegate);
            if (result.isPresent()) {
                return result;
            }
        }
        LOG.warn("CompositePrincipalMapper: no delegate resolved {} '{}'", principalType, name);
        if (metricsEmitter != null) {
            metricsEmitter.recordUnmappedPrincipal(principalType);
        }
        return Optional.empty();
    }
}
