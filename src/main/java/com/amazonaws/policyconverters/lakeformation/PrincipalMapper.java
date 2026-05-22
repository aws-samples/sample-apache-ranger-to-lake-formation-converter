package com.amazonaws.policyconverters.lakeformation;

import java.util.Optional;

public interface PrincipalMapper {
    Optional<String> resolveUser(String rangerUser);
    Optional<String> resolveGroup(String rangerGroup);
    Optional<String> resolveRole(String rangerRole);
}
