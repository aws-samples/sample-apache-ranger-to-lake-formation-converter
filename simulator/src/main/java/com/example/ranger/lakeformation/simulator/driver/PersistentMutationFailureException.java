package com.example.ranger.lakeformation.simulator.driver;

/**
 * Thrown when mutations for a single Ranger service instance fail repeatedly
 * (consecutively) beyond a configured threshold.
 *
 * <p>This makes a misconfigured or missing service fail loudly instead of being
 * silently swallowed as per-operation WARN logs — a service whose every policy
 * POST is rejected would otherwise masquerade as "all permissions correct"
 * because none of its policies ever reach Ranger.
 */
public class PersistentMutationFailureException extends RuntimeException {

    private final String serviceName;
    private final int consecutiveFailures;

    public PersistentMutationFailureException(String serviceName, int consecutiveFailures, String lastError) {
        super("Persistent mutation failures for Ranger service '" + serviceName + "': "
                + consecutiveFailures + " consecutive failures. Last error: " + lastError
                + ". Is the service instance installed in Ranger and are its access types valid?");
        this.serviceName = serviceName;
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
}
