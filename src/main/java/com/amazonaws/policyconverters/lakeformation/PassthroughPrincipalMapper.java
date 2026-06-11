package com.amazonaws.policyconverters.lakeformation;

import java.util.Optional;

public class PassthroughPrincipalMapper implements PrincipalMapper {

    private static final String PLACEHOLDER_ACCOUNT = "000000000000";

    @Override
    public Optional<String> resolveUser(String name) {
        if (name == null) return Optional.empty();
        return Optional.of("arn:aws:iam::" + PLACEHOLDER_ACCOUNT + ":user/ranger-user/" + name);
    }

    @Override
    public Optional<String> resolveGroup(String name) {
        if (name == null) return Optional.empty();
        return Optional.of("arn:aws:iam::" + PLACEHOLDER_ACCOUNT + ":group/ranger-group/" + name);
    }

    @Override
    public Optional<String> resolveRole(String name) {
        if (name == null) return Optional.empty();
        return Optional.of("arn:aws:iam::" + PLACEHOLDER_ACCOUNT + ":role/ranger-role/" + name);
    }
}
