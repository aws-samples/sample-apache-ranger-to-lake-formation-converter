package com.amazonaws.policyconverters.lakeformation;

import java.util.Optional;

public class PassthroughPrincipalMapper implements PrincipalMapper {

    @Override
    public Optional<String> resolveUser(String name) {
        return Optional.of("ranger-user:" + name);
    }

    @Override
    public Optional<String> resolveGroup(String name) {
        return Optional.of("ranger-group:" + name);
    }

    @Override
    public Optional<String> resolveRole(String name) {
        return Optional.of("ranger-role:" + name);
    }
}
