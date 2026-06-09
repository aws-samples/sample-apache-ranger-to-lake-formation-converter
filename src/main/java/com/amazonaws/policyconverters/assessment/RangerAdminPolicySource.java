package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.ConversionServerMain;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RangerAdminPolicySource implements PolicySource {

    private static final Logger LOG = LoggerFactory.getLogger(RangerAdminPolicySource.class);

    private final String rangerAdminUrl;
    private final String username;
    private final String password;
    private final List<RangerServiceConfig> services;

    public RangerAdminPolicySource(String rangerAdminUrl, String username, String password,
                                    List<RangerServiceConfig> services) {
        if (rangerAdminUrl == null || rangerAdminUrl.isBlank()) {
            throw new IllegalArgumentException("rangerAdminUrl must not be blank");
        }
        this.rangerAdminUrl = rangerAdminUrl;
        this.username = username;
        this.password = password;
        this.services = services != null ? services : List.of();
    }

    @Override
    public String sourceLabel() {
        return "ranger-admin:" + rangerAdminUrl;
    }

    @Override
    public List<ServicePolicyBatch> load() {
        List<ServicePolicyBatch> batches = new ArrayList<>();
        List<RangerServiceConfig> toFetch = services.isEmpty()
                ? List.of(new RangerServiceConfig("lakeformation", "lakeformation", null, null))
                : services;

        for (RangerServiceConfig svc : toFetch) {
            String instanceName = svc.getServiceInstanceName();
            ServicePolicies sp = ConversionServerMain.fetchPoliciesFromRangerAdmin(
                    rangerAdminUrl, username, password, instanceName);

            if (sp == null || sp.getPolicies() == null) {
                LOG.warn("No policies returned from Ranger Admin for service '{}'", instanceName);
                batches.add(ServicePolicyBatch.skipped(
                        instanceName, svc.getServiceType(), 0,
                        "fetch failed or returned no data"));
            } else {
                List<RangerPolicy> policies = sp.getPolicies();
                // fetchPoliciesFromRangerAdmin already strips disabled policies, so
                // rawPolicyCount == policies.size() (post-filter). The "pre-filter" semantic
                // of rawPolicyCount only holds for RangerExportFilePolicySource.
                batches.add(ServicePolicyBatch.assessed(
                        instanceName, svc.getServiceType(), policies, policies.size()));
            }
        }
        return batches;
    }
}
