package com.amazonaws.policyconverters.assessment;

import java.util.List;

public interface PolicySource {
    List<ServicePolicyBatch> load();
    String sourceLabel();
}
