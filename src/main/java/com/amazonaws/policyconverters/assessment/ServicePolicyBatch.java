package com.amazonaws.policyconverters.assessment;

import org.apache.ranger.plugin.model.RangerPolicy;

import java.util.Collections;
import java.util.List;

public class ServicePolicyBatch {

    private final String serviceName;
    private final String serviceType;
    private final List<RangerPolicy> policies;
    private final int rawPolicyCount;
    private final String skipReason;

    private ServicePolicyBatch(String serviceName, String serviceType,
                                List<RangerPolicy> policies, int rawPolicyCount,
                                String skipReason) {
        this.serviceName = serviceName;
        this.serviceType = serviceType;
        this.policies = Collections.unmodifiableList(policies);
        this.rawPolicyCount = rawPolicyCount;
        this.skipReason = skipReason;
    }

    public static ServicePolicyBatch assessed(String serviceName, String serviceType,
                                               List<RangerPolicy> policies, int rawPolicyCount) {
        return new ServicePolicyBatch(serviceName, serviceType, policies, rawPolicyCount, null);
    }

    public static ServicePolicyBatch skipped(String serviceName, String serviceType,
                                              int rawPolicyCount, String skipReason) {
        return new ServicePolicyBatch(serviceName, serviceType,
                Collections.emptyList(), rawPolicyCount, skipReason);
    }

    public String getServiceName()    { return serviceName; }
    public String getServiceType()    { return serviceType; }
    public List<RangerPolicy> getPolicies() { return policies; }
    public int getRawPolicyCount()    { return rawPolicyCount; }
    public String getSkipReason()     { return skipReason; }
    public boolean isSkipped()        { return skipReason != null; }
}
