package com.example.ranger.lakeformation.simulator.workload;

import java.util.Map;

@FunctionalInterface
public interface PolicyGenerator {
    Map<String, Object> generate(String policyId);
}
