package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Abstract base class that encapsulates Ranger plugin lifecycle, configuration,
 * and adapter registration for a given service type.
 *
 * <p>Each subclass provides its service type identifier, service instance name,
 * {@link SourcePolicyAdapter} implementation, and service definition resource path.
 *
 * <p>The {@link RangerBasePlugin} is composed (not inherited) inside this class,
 * avoiding tight coupling and allowing each service to have its own plugin instance
 * with independent lifecycle.
 */
public abstract class BaseRangerService {

    private static final Logger LOG = LoggerFactory.getLogger(BaseRangerService.class);

    private final String serviceType;
    private final String serviceInstanceName;
    private final RangerBasePlugin plugin;
    private volatile ServicePolicies latestPolicies;
    private volatile List<RangerPolicy> lastKnownGoodPolicies = Collections.emptyList();

    /**
     * Creates a new BaseRangerService with the given service type and instance name.
     *
     * @param serviceType        the Ranger service type (e.g., "lakeformation", "hive")
     * @param serviceInstanceName the Ranger Admin service instance name
     */
    protected BaseRangerService(String serviceType, String serviceInstanceName) {
        this.serviceType = serviceType;
        this.serviceInstanceName = serviceInstanceName;
        this.plugin = new RangerBasePlugin(serviceType, serviceInstanceName);
        LOG.info("BaseRangerService created: serviceType={}, serviceInstanceName={}",
                serviceType, serviceInstanceName);
    }

    /**
     * Initialize the plugin (registers with Ranger Admin).
     */
    public void init() {
        LOG.info("Initializing Ranger plugin: serviceType={}, serviceInstanceName={}",
                serviceType, serviceInstanceName);
        plugin.init();
    }

    /**
     * Returns the latest policies from the underlying Ranger plugin.
     * Updates {@link #lastKnownGoodPolicies} on success (non-null policies with
     * a non-null policy list).
     *
     * @return the latest {@link ServicePolicies}, or null if no policies are available
     */
    public ServicePolicies getLatestPolicies() {
        ServicePolicies sp = latestPolicies;
        if (sp != null && sp.getPolicies() != null) {
            lastKnownGoodPolicies = sp.getPolicies();
            LOG.debug("Updated lastKnownGoodPolicies: serviceType={}, count={}",
                    serviceType, lastKnownGoodPolicies.size());
        }
        return sp;
    }

    /**
     * Returns the last successfully fetched policies for fault tolerance.
     * If no policies have been successfully fetched yet, returns an empty list.
     *
     * @return the last known good policy list, never null
     */
    public List<RangerPolicy> getLastKnownGoodPolicies() {
        return lastKnownGoodPolicies;
    }

    /**
     * Subclass provides its {@link SourcePolicyAdapter} implementation.
     *
     * @param awsContext the AWS context for ARN construction
     * @return the adapter for this service type
     */
    public abstract SourcePolicyAdapter createAdapter(AwsContext awsContext);

    /**
     * Subclass provides the classpath resource path to its bundled service
     * definition JSON file.
     *
     * @return the resource path (e.g., "/ranger-servicedef-lakeformation.json")
     */
    public abstract String getServiceDefinitionResourcePath();

    /**
     * Returns the Ranger service type identifier.
     *
     * @return the service type (e.g., "lakeformation", "hive", "presto", "trino")
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Returns the Ranger Admin service instance name.
     *
     * @return the service instance name
     */
    public String getServiceInstanceName() {
        return serviceInstanceName;
    }

    /**
     * Returns the underlying Ranger plugin instance.
     * Visible for subclasses and testing.
     *
     * @return the composed {@link RangerBasePlugin}
     */
    protected RangerBasePlugin getPlugin() {
        return plugin;
    }

    /**
     * Sets the latest policies received from Ranger Admin.
     * Called internally when the plugin receives a policy refresh.
     *
     * @param policies the updated service policies
     */
    protected void setLatestPolicies(ServicePolicies policies) {
        this.latestPolicies = policies;
    }
}
