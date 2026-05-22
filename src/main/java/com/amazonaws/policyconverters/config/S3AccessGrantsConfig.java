package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record S3AccessGrantsConfig(
    @JsonProperty("instanceArn") String instanceArn,
    @JsonProperty("accountId") String accountId
) {}
