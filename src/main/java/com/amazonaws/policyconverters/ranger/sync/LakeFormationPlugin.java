package com.amazonaws.policyconverters.ranger.sync;

import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Ranger plugin for Lake Formation synchronization.
 * Extends RangerBasePlugin to register with Ranger Admin using the custom
 * "lakeformation" service definition and receive policy updates via the
 * RangerBasePlugin policy refresh mechanism.
 *
 * When policies are refreshed from Ranger Admin, {@link #setPolicies(ServicePolicies)}
 * is called with the updated policy set. This class captures the latest policies
 * and delegates diff computation and LF application to the SyncService.
 */
public class LakeFormationPlugin extends RangerBasePlugin {

    private static final Logger LOG = LoggerFactory.getLogger(LakeFormationPlugin.class);

    public static final String SERVICE_TYPE = "lakeformation";
    public static final String APP_ID = "lakeformation";

    private volatile ServicePolicies latestPolicies;
    private volatile PolicyUpdateListener policyUpdateListener;

    /**
     * Callback interface for notifying listeners when policies are updated.
     * The SyncService implements this to receive policy change notifications.
     */
    @FunctionalInterface
    public interface PolicyUpdateListener {
        /**
         * Called when new policies are received from Ranger Admin.
         *
         * @param policies the updated service policies
         */
        void onPoliciesUpdated(ServicePolicies policies);
    }

    public LakeFormationPlugin() {
        super(SERVICE_TYPE, APP_ID);
        LOG.info("LakeFormationPlugin created with serviceType={}, appId={}", SERVICE_TYPE, APP_ID);
    }

    /**
     * Register a listener to be notified when policies are updated.
     *
     * @param listener the listener to register
     */
    public void setPolicyUpdateListener(PolicyUpdateListener listener) {
        this.policyUpdateListener = listener;
    }

    /**
     * Called by the RangerBasePlugin framework when policies are refreshed
     * from Ranger Admin. Captures the latest policies and notifies the
     * registered listener (SyncService) for diff computation and application.
     *
     * @param policies the updated service policies from Ranger Admin
     */
    @Override
    public void setPolicies(ServicePolicies policies) {
        if (policies == null) {
            LOG.warn("Received null policies from Ranger Admin, ignoring update");
            return;
        }

        long policyVersion = policies.getPolicyVersion() != null ? policies.getPolicyVersion() : -1L;
        int policyCount = policies.getPolicies() != null ? policies.getPolicies().size() : 0;

        LOG.info("Received policy update from Ranger Admin: version={}, policyCount={}",
                policyVersion, policyCount);

        // Delegate to parent to update the internal policy engine
        super.setPolicies(policies);

        // Store the latest policies
        this.latestPolicies = policies;

        // Notify the listener (SyncService) about the policy update
        PolicyUpdateListener listener = this.policyUpdateListener;
        if (listener != null) {
            try {
                listener.onPoliciesUpdated(policies);
            } catch (Exception e) {
                LOG.error("Error in policy update listener: {}", e.getMessage(), e);
            }
        } else {
            LOG.debug("No policy update listener registered, skipping notification");
        }
    }

    /**
     * Returns the latest policies received from Ranger Admin.
     *
     * @return the latest ServicePolicies, or null if no policies have been received yet
     */
    public ServicePolicies getLatestPolicies() {
        return latestPolicies;
    }

    /**
     * Returns the list of Ranger policies from the latest update.
     *
     * @return list of policies, or empty list if no policies have been received
     */
    public List<RangerPolicy> getLatestPolicyList() {
        ServicePolicies sp = latestPolicies;
        if (sp == null || sp.getPolicies() == null) {
            return Collections.emptyList();
        }
        return sp.getPolicies();
    }
}
