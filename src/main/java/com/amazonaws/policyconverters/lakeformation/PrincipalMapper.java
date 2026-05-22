package com.amazonaws.policyconverters.lakeformation;

import java.util.Optional;

/**
 * Maps Ranger principals (users, groups, roles) to AWS principal ARNs
 * for use as Lake Formation {@code dataLakePrincipalIdentifier} values.
 * Returns {@link java.util.Optional#empty()} when a principal cannot be resolved.
 */
public interface PrincipalMapper {
    Optional<String> resolveUser(String rangerUser);
    Optional<String> resolveGroup(String rangerGroup);
    Optional<String> resolveRole(String rangerRole);
}
