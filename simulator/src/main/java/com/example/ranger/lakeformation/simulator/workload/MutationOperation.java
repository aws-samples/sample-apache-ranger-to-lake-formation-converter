package com.example.ranger.lakeformation.simulator.workload;

import java.time.Instant;

public sealed interface MutationOperation permits
        MutationOperation.CreatePolicy,
        MutationOperation.UpdatePolicy,
        MutationOperation.DisablePolicy,
        MutationOperation.EnablePolicy,
        MutationOperation.DeletePolicy {

    Instant timestamp();

    record CreatePolicy(Instant timestamp, String policyId, Object policyPayload) implements MutationOperation {}
    record UpdatePolicy(Instant timestamp, String policyId, Object policyPayload) implements MutationOperation {}
    record DisablePolicy(Instant timestamp, String policyId) implements MutationOperation {}
    record EnablePolicy(Instant timestamp, String policyId) implements MutationOperation {}
    record DeletePolicy(Instant timestamp, String policyId) implements MutationOperation {}
}
