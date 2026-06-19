package com.amazonaws.policyconverters.s3accessgrants;

import com.amazonaws.policyconverters.config.S3AccessGrantsConfig;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.AccessGrantsLocationConfiguration;
import software.amazon.awssdk.services.s3control.model.CreateAccessGrantRequest;
import software.amazon.awssdk.services.s3control.model.CreateAccessGrantResponse;
import software.amazon.awssdk.services.s3control.model.Grantee;
import software.amazon.awssdk.services.s3control.model.GranteeType;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsResponse;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse;
import software.amazon.awssdk.services.s3control.model.Permission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wrapper around the AWS S3Control SDK that manages S3 Access Grants operations.
 *
 * <p>Handles grant creation and deletion with retry/backoff, location validation,
 * and dead-letter logging for unregistered prefixes.
 *
 * <p>Throttling retries are handled internally by the AWS SDK v2 retry policy.
 * Transient failures in {@link #applyBatch} are retried with exponential backoff.
 */
public class S3AccessGrantsClient {

    private static final Logger LOG = LoggerFactory.getLogger(S3AccessGrantsClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final S3ControlClient s3control;
    private final String accountId;
    private final String instanceArn;
    private final DeadLetterLogger deadLetterLogger;

    /** Cached registered location scopes; cleared at the start of each applyBatch call. */
    private Set<String> cachedLocations = null;

    /**
     * Constructs an S3AccessGrantsClient using default AWS credentials.
     *
     * @param config          S3 Access Grants configuration (accountId, instanceArn)
     * @param deadLetterLogger logger for operations that cannot be applied (may be null)
     */
    public S3AccessGrantsClient(S3AccessGrantsConfig config, DeadLetterLogger deadLetterLogger) {
        this.accountId = config.accountId();
        this.instanceArn = config.instanceArn();
        this.deadLetterLogger = deadLetterLogger;
        this.s3control = S3ControlClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Constructor for testing with an injected client.
     * Accessible from integration tests in other packages.
     */
    public S3AccessGrantsClient(S3AccessGrantsConfig config, S3ControlClient s3control,
                         DeadLetterLogger deadLetterLogger) {
        this.accountId = config.accountId();
        this.instanceArn = config.instanceArn();
        this.s3control = s3control;
        this.deadLetterLogger = deadLetterLogger;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * List all registered S3 Access Grants location scopes for the account.
     *
     * <p>Results are cached — repeated calls within a batch cycle return the same set.
     * Call {@link #applyBatch} to reset the cache for the next cycle.
     *
     * @return set of location scope strings (e.g. {@code s3://my-bucket/prefix/})
     */
    public Set<String> listRegisteredLocations() {
        if (cachedLocations != null) {
            return cachedLocations;
        }

        Set<String> locations = new HashSet<>();
        String nextToken = null;

        do {
            String token = nextToken;
            ListAccessGrantsLocationsResponse response = s3control.listAccessGrantsLocations(
                    r -> {
                        r.accountId(accountId);
                        if (token != null) { // nosemgrep: eqeq
                            r.nextToken(token);
                        }
                    });

            if (response.hasAccessGrantsLocationsList()) {
                for (ListAccessGrantsLocationsEntry entry : response.accessGrantsLocationsList()) {
                    if (entry.locationScope() != null) {
                        locations.add(entry.locationScope());
                    }
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null); // nosemgrep: eqeq

        LOG.debug("Loaded {} registered S3 Access Grants locations", locations.size());
        cachedLocations = locations;
        return cachedLocations;
    }

    /**
     * Create an S3 Access Grant for the given operation.
     *
     * <p>Before calling the API, validates that the operation's {@code s3Prefix} is covered
     * by at least one registered location. If not covered, logs a warning, writes to the
     * dead-letter log, and returns {@code null} without calling the AWS API.
     *
     * @param op the grant operation to apply
     * @return the new grant ID, or {@code null} if the prefix is not covered by a registered location
     */
    public String createGrant(S3AccessGrantOperation op) {
        Set<String> locations = listRegisteredLocations();

        String matchedLocation = findMatchingLocation(op.s3Prefix(), locations);
        if (matchedLocation == null) {
            LOG.warn("Unregistered S3 location: prefix {} is not covered by any registered location (principal={})",
                    op.s3Prefix(), op.principalArn());
            writeDeadLetter(op, "Unregistered S3 location: prefix not covered by any registered Access Grants location");
            return null;
        }

        String subPrefix = resolveSubPrefix(op.s3Prefix(), matchedLocation);
        String locationId = resolveLocationId(op.s3Prefix(), locations);

        CreateAccessGrantRequest.Builder rb = CreateAccessGrantRequest.builder()
                .accountId(accountId)
                .grantee(buildGrantee(op.principalArn()))
                .accessGrantsLocationId(locationId)
                .permission(toSdkPermission(op.permission()));
        if (subPrefix != null && !subPrefix.isEmpty()) {
            rb.accessGrantsLocationConfiguration(
                    AccessGrantsLocationConfiguration.builder().s3SubPrefix(subPrefix).build());
        }
        // Note: instanceArn is not a parameter accepted by the S3Control createAccessGrant API;
        // it is implicitly associated with the account.
        CreateAccessGrantResponse response = s3control.createAccessGrant(rb.build());

        String grantId = response.accessGrantId();
        LOG.info("Created S3 Access Grant: grantId={}, principal={}, prefix={}, permission={}",
                grantId, op.principalArn(), op.s3Prefix(), op.permission());
        return grantId;
    }

    /**
     * Delete an S3 Access Grant by its grant ID.
     *
     * @param grantId the grant ID to delete
     */
    public void deleteGrant(String grantId) {
        LOG.info("Deleting S3 Access Grant: grantId={}", grantId);
        s3control.deleteAccessGrant(r -> r
                .accountId(accountId)
                .accessGrantId(grantId));
    }

    /**
     * List all existing S3 Access Grants for the account.
     *
     * @return list of operations representing current live grants (with grantId populated)
     */
    public List<S3AccessGrantOperation> listGrants() {
        List<S3AccessGrantOperation> result = new ArrayList<>();
        String nextToken = null;

        do {
            String token = nextToken;
            ListAccessGrantsResponse response = s3control.listAccessGrants(r -> {
                r.accountId(accountId);
                if (token != null) {  // nosemgrep: eqeq
                    r.nextToken(token);
                }
            });

            if (response.hasAccessGrantsList()) {
                for (ListAccessGrantEntry entry : response.accessGrantsList()) {
                    S3AccessGrantOperation op = mapEntryToOperation(entry);
                    if (op != null) {
                        result.add(op);
                    }
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null); // nosemgrep: eqeq

        LOG.debug("Listed {} existing S3 Access Grants", result.size());
        return result;
    }

    /**
     * Result of a {@link #applyBatch} call.
     *
     * @param grants  number of grants successfully created
     * @param revokes number of grants successfully deleted
     * @param skipped number of operations skipped (e.g. unregistered prefix or grant not found)
     */
    public record BatchResult(int grants, int revokes, int skipped) {}

    /**
     * Apply a batch of GRANT and REVOKE operations.
     *
     * <p>Clears the location cache at the start so each batch picks up any newly registered
     * locations. REVOKEs look up the live grant ID via {@link #listGrants()} — if a matching
     * grant is not found it is skipped with a warning. Transient AWS errors are retried with
     * exponential backoff up to {@code maxRetries} attempts.
     *
     * @param ops            list of operations to apply
     * @param maxRetries     maximum number of retry attempts per operation
     * @param retryBackoffMs initial backoff in milliseconds (doubled on each retry)
     * @return a {@link BatchResult} summarising the outcome
     */
    public BatchResult applyBatch(List<S3AccessGrantOperation> ops, int maxRetries, long retryBackoffMs) {
        // Reset location cache so this batch picks up any newly registered locations
        cachedLocations = null;

        List<S3AccessGrantOperation> grants = new ArrayList<>();
        List<S3AccessGrantOperation> revokes = new ArrayList<>();

        for (S3AccessGrantOperation op : ops) {
            if (op.type() == OperationType.GRANT) {
                grants.add(op);
            } else {
                revokes.add(op);
            }
        }

        // Build lookup map for REVOKEs: (principalArn, s3Prefix, permission) -> grantId
        Map<GrantKey, String> grantIdLookup = new HashMap<>();
        if (!revokes.isEmpty()) {
            for (S3AccessGrantOperation existing : listGrants()) {
                GrantKey key = new GrantKey(existing.principalArn(), existing.s3Prefix(), existing.permission());
                grantIdLookup.put(key, existing.grantId());
            }
        }

        int grantCount = 0;
        int revokeCount = 0;
        int skippedCount = 0;

        for (S3AccessGrantOperation op : grants) {
            try {
                String grantId = executeWithRetry(() -> createGrant(op), "GRANT", op, maxRetries, retryBackoffMs);
                if (grantId != null) {
                    grantCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                LOG.error("GRANT failed for principal={}, prefix={}: {}",
                        op.principalArn(), op.s3Prefix(), e.getMessage());
                writeDeadLetter(op, e.getMessage());
                skippedCount++;
            }
        }

        for (S3AccessGrantOperation op : revokes) {
            GrantKey key = new GrantKey(op.principalArn(), op.s3Prefix(), op.permission());
            String grantId = grantIdLookup.get(key);
            if (grantId == null) {
                LOG.warn("REVOKE skipped — no matching grant found for principal={}, prefix={}, permission={}",
                        op.principalArn(), op.s3Prefix(), op.permission());
                skippedCount++;
                continue;
            }
            try {
                executeWithRetry(() -> { deleteGrant(grantId); return null; }, "REVOKE", op, maxRetries, retryBackoffMs);
                revokeCount++;
            } catch (Exception e) {
                LOG.error("REVOKE failed for grantId={}, principal={}, prefix={}: {}",
                        grantId, op.principalArn(), op.s3Prefix(), e.getMessage());
                writeDeadLetter(op, e.getMessage());
                skippedCount++;
            }
        }

        BatchResult result = new BatchResult(grantCount, revokeCount, skippedCount);
        LOG.info("S3 Access Grants batch complete: grants={}, revokes={}, skipped={}",
                grantCount, revokeCount, skippedCount);
        return result;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Find the longest registered location that is a prefix of the given s3Prefix.
     *
     * @param s3Prefix  the prefix to match
     * @param locations the set of registered location scopes
     * @return the best matching location, or {@code null} if none match
     */
    private String findMatchingLocation(String s3Prefix, Set<String> locations) {
        String best = null;
        for (String loc : locations) {
            if (s3Prefix.startsWith(loc)) {
                if (best == null || loc.length() > best.length()) {
                    best = loc;
                }
            }
        }
        return best;
    }

    /**
     * Resolve the access grants location ID for the given prefix.
     *
     * <p>Uses a second API call to retrieve the location entry that covers the prefix.
     * Returns "default" as fallback if no match is found (should not normally occur
     * since {@link #createGrant} already verified coverage).
     */
    private String resolveLocationId(String s3Prefix, Set<String> locations) {
        String matchedScope = findMatchingLocation(s3Prefix, locations);
        if (matchedScope == null) {
            return "default";
        }

        // Retrieve the location entry to get its ID
        String scopeForSearch = matchedScope;
        ListAccessGrantsLocationsResponse response = s3control.listAccessGrantsLocations(
                r -> r.accountId(accountId).locationScope(scopeForSearch));

        if (response.hasAccessGrantsLocationsList() && !response.accessGrantsLocationsList().isEmpty()) {
            return response.accessGrantsLocationsList().get(0).accessGrantsLocationId();
        }
        return "default";
    }

    /**
     * Compute the sub-prefix relative to the matched registered location.
     *
     * <p>For example, if the matched location is {@code s3://my-bucket/} and the grant
     * prefix is {@code s3://my-bucket/data/}, the sub-prefix is {@code data/}.
     * Returns {@code null} (no sub-prefix) when the prefix exactly equals the location.
     */
    private String resolveSubPrefix(String s3Prefix, String matchedLocation) {
        if (s3Prefix.equals(matchedLocation)) {
            return null;
        }
        return s3Prefix.substring(matchedLocation.length());
    }

    /**
     * Build an IAM-type {@link Grantee} for the given principal ARN.
     */
    private Grantee buildGrantee(String principalArn) {
        return Grantee.builder()
                .granteeType(GranteeType.IAM)
                .granteeIdentifier(principalArn)
                .build();
    }

    /**
     * Map our {@link S3AccessGrantPermission} to the SDK {@link Permission} enum.
     */
    private Permission toSdkPermission(S3AccessGrantPermission p) {
        return switch (p) {
            case READ -> Permission.READ;
            case WRITE -> Permission.WRITE;
            case READWRITE -> Permission.READWRITE;
        };
    }

    /**
     * Map our {@link Permission} SDK enum to our {@link S3AccessGrantPermission}.
     * Returns {@code null} for unknown values.
     */
    private S3AccessGrantPermission fromSdkPermission(Permission p) {
        return switch (p) {
            case READ -> S3AccessGrantPermission.READ;
            case WRITE -> S3AccessGrantPermission.WRITE;
            case READWRITE -> S3AccessGrantPermission.READWRITE;
            default -> null;
        };
    }

    /**
     * Map a {@link ListAccessGrantEntry} to an {@link S3AccessGrantOperation}.
     * Returns {@code null} if the entry cannot be mapped (e.g. unknown permission).
     */
    private S3AccessGrantOperation mapEntryToOperation(ListAccessGrantEntry entry) {
        if (entry.grantee() == null || entry.permission() == null) {
            return null;
        }
        S3AccessGrantPermission permission = fromSdkPermission(entry.permission());
        if (permission == null) {
            LOG.debug("Skipping grant {} with unknown permission {}", entry.accessGrantId(), entry.permissionAsString());
            return null;
        }

        // grantScope is the effective S3 prefix (location scope + sub-prefix)
        String s3Prefix = entry.grantScope();
        if (s3Prefix == null) {
            // Fall back to location scope if grantScope is absent
            AccessGrantsLocationConfiguration cfg = entry.accessGrantsLocationConfiguration();
            s3Prefix = cfg != null && cfg.s3SubPrefix() != null ? cfg.s3SubPrefix() : "";
        }

        String principalArn = entry.grantee().granteeIdentifier();

        return new S3AccessGrantOperation(
                OperationType.GRANT,
                principalArn,
                s3Prefix,
                permission,
                entry.accessGrantId(),
                null);
    }

    /**
     * Execute a retryable action with exponential backoff.
     *
     * <p>Retries on any {@link Exception}. After exhausting retries, rethrows the last exception.
     *
     * @param action         the action to execute
     * @param operationType  label for logging
     * @param op             the operation being applied (for logging)
     * @param maxRetries     maximum number of additional attempts after the first failure
     * @param retryBackoffMs initial backoff in milliseconds
     * @param <T>            return type of the action
     * @return the action's return value
     * @throws Exception if all attempts fail
     */
    private <T> T executeWithRetry(RetryableAction<T> action, String operationType,
                                   S3AccessGrantOperation op, int maxRetries, long retryBackoffMs)
            throws Exception {
        int attempt = 0;
        long backoffMs = retryBackoffMs;

        while (true) {
            try {
                return action.execute();
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    LOG.error("{} failed after {} retries for principal={}, prefix={}: {}",
                            operationType, maxRetries, op.principalArn(), op.s3Prefix(), e.getMessage());
                    throw e;
                }
                LOG.warn("{} attempt {} failed for principal={}, prefix={}, retrying in {}ms: {}",
                        operationType, attempt, op.principalArn(), op.s3Prefix(), backoffMs, e.getMessage());
                doSleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000L);
            }
        }
    }

    /**
     * Write a failed S3 Access Grants operation to the dead-letter log if a logger is configured.
     *
     * <p>{@link DeadLetterLogger#logFailedOperation} is typed to {@code LFPermissionOperation} and
     * cannot be called directly with an {@code S3AccessGrantOperation}. Instead, we build a JSON
     * entry that mirrors the dead-letter format and write it via the logger's underlying writer
     * using {@link DeadLetterLogger#logS3AgFailure}.
     */
    private void writeDeadLetter(S3AccessGrantOperation op, String errorMessage) {
        LOG.warn("DEAD_LETTER principal={} s3Prefix={} permission={} error={}",
                op.principalArn(), op.s3Prefix(), op.permission(), errorMessage);
        if (deadLetterLogger != null) {
            try {
                ObjectNode entry = MAPPER.createObjectNode();
                entry.put("timestamp", java.time.Instant.now().toString());
                entry.put("type", "S3_ACCESS_GRANT");
                entry.put("operation", op.type() != null ? op.type().name() : "UNKNOWN");
                entry.put("principal", op.principalArn());
                entry.put("s3Prefix", op.s3Prefix());
                entry.put("permission", op.permission() != null ? op.permission().name() : "UNKNOWN");
                entry.put("error", errorMessage);
                deadLetterLogger.logEntry(MAPPER.writeValueAsString(entry));
            } catch (Exception e) {
                LOG.error("Failed to write S3AG dead-letter entry: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Sleep for the given duration, handling InterruptedException gracefully.
     */
    private void doSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Retry sleep interrupted");
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /** Functional interface for retryable actions that may throw. */
    @FunctionalInterface
    private interface RetryableAction<T> {
        T execute() throws Exception;
    }

    /** Composite key for deduplicating existing grants during REVOKE lookup. */
    private record GrantKey(String principalArn, String s3Prefix, S3AccessGrantPermission permission) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GrantKey that)) return false;
            return Objects.equals(principalArn, that.principalArn)
                    && Objects.equals(s3Prefix, that.s3Prefix)
                    && permission == that.permission;
        }

        @Override
        public int hashCode() {
            return Objects.hash(principalArn, s3Prefix, permission);
        }
    }
}
