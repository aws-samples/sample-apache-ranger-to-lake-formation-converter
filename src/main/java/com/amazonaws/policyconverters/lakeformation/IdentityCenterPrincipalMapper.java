package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.IdentityCenterConfig;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;
import software.amazon.awssdk.services.identitystore.model.AlternateIdentifier;
import software.amazon.awssdk.services.identitystore.model.GetGroupIdRequest;
import software.amazon.awssdk.services.identitystore.model.GetGroupIdResponse;
import software.amazon.awssdk.services.identitystore.model.GetUserIdRequest;
import software.amazon.awssdk.services.identitystore.model.GetUserIdResponse;
import software.amazon.awssdk.services.identitystore.model.ResourceNotFoundException;
import software.amazon.awssdk.services.identitystore.model.UniqueAttribute;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps Ranger principals (users and groups) to AWS Identity Center principal ARNs
 * by querying the IdentityStore API. Roles are not supported by Identity Center
 * and always return {@link Optional#empty()}.
 *
 * <p>Resolved ARNs are cached in-memory with a configurable TTL to reduce
 * IdentityStore API call volume. Cache entries are never locked during I/O;
 * concurrent threads may each call the API for the same key — this is a benign
 * race where the last writer wins with an identical value.
 */
public class IdentityCenterPrincipalMapper implements PrincipalMapper {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityCenterPrincipalMapper.class);

    private final String identityStoreId;
    private final String accountId;
    private final IdentitystoreClient identityStoreClient;
    private final MetricsEmitter metricsEmitter;   // nullable, null-guarded before use
    private final long cacheTtlMs;                 // idcConfig.getCacheTtlMinutes() * 60_000L
    private final ConcurrentHashMap<String, CacheEntry> cache;

    private static final class CacheEntry {
        final String arn;
        final Instant expiresAt;

        CacheEntry(String arn, Instant expiresAt) {
            this.arn = arn;
            this.expiresAt = expiresAt;
        }
    }

    public IdentityCenterPrincipalMapper(IdentityCenterConfig config,
                                         IdentitystoreClient identityStoreClient,
                                         MetricsEmitter metricsEmitter) {
        this.identityStoreId = config.getIdentityStoreId();
        this.accountId = config.getAccountId();
        this.cacheTtlMs = (long) config.getCacheTtlMinutes() * 60_000L;
        this.identityStoreClient = identityStoreClient;
        this.metricsEmitter = metricsEmitter;
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Resolves a Ranger user name to an Identity Center user ARN.
     *
     * @param rangerUser the Ranger user name (must match the Identity Center {@code userName} attribute)
     * @return the Identity Center user ARN, or {@link Optional#empty()} if unresolvable
     */
    @Override
    public Optional<String> resolveUser(String rangerUser) {
        if (rangerUser == null) {
            LOG.warn("Attempted to resolve null user principal, returning empty");
            return Optional.empty();
        }

        String cacheKey = "user:" + rangerUser;

        // Check cache first (read-then-put, no lock held during I/O)
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) {
            return Optional.of(cached.arn);
        }

        try {
            GetUserIdRequest request = GetUserIdRequest.builder()
                    .identityStoreId(identityStoreId)
                    .alternateIdentifier(AlternateIdentifier.builder()
                            .uniqueAttribute(UniqueAttribute.builder()
                                    .attributePath("userName")
                                    .attributeValue(Document.fromString(rangerUser))
                                    .build())
                            .build())
                    .build();
            GetUserIdResponse response = identityStoreClient.getUserId(request);

            String arn = "arn:aws:identitystore::" + accountId + ":user/" + response.userId();
            cache.put(cacheKey, new CacheEntry(arn, Instant.now().plusMillis(cacheTtlMs)));
            return Optional.of(arn);

        } catch (ResourceNotFoundException e) {
            LOG.warn("No Identity Center user found for '{}', skipping", rangerUser);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedPrincipal("user");
            }
            return Optional.empty();

        } catch (SdkException e) {
            LOG.warn("IdentityStore API error resolving user '{}': {}", rangerUser, e.getMessage());
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedPrincipal("user");
            }
            return Optional.empty();
        }
    }

    /**
     * Resolves a Ranger group name to an Identity Center group ARN.
     *
     * @param rangerGroup the Ranger group name (must match the Identity Center {@code displayName} attribute)
     * @return the Identity Center group ARN, or {@link Optional#empty()} if unresolvable
     */
    @Override
    public Optional<String> resolveGroup(String rangerGroup) {
        if (rangerGroup == null) {
            LOG.warn("Attempted to resolve null group principal, returning empty");
            return Optional.empty();
        }

        String cacheKey = "group:" + rangerGroup;

        // Check cache first (read-then-put, no lock held during I/O)
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) {
            return Optional.of(cached.arn);
        }

        try {
            GetGroupIdRequest request = GetGroupIdRequest.builder()
                    .identityStoreId(identityStoreId)
                    .alternateIdentifier(AlternateIdentifier.builder()
                            .uniqueAttribute(UniqueAttribute.builder()
                                    .attributePath("displayName")
                                    .attributeValue(Document.fromString(rangerGroup))
                                    .build())
                            .build())
                    .build();
            GetGroupIdResponse response = identityStoreClient.getGroupId(request);

            String arn = "arn:aws:identitystore::" + accountId + ":group/" + response.groupId();
            cache.put(cacheKey, new CacheEntry(arn, Instant.now().plusMillis(cacheTtlMs)));
            return Optional.of(arn);

        } catch (ResourceNotFoundException e) {
            LOG.warn("No Identity Center group found for '{}', skipping", rangerGroup);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedPrincipal("group");
            }
            return Optional.empty();

        } catch (SdkException e) {
            LOG.warn("IdentityStore API error resolving group '{}': {}", rangerGroup, e.getMessage());
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedPrincipal("group");
            }
            return Optional.empty();
        }
    }

    /**
     * Identity Center does not support role principals; always returns {@link Optional#empty()}.
     *
     * @param rangerRole the Ranger role name (unused)
     * @return always {@link Optional#empty()}
     */
    @Override
    public Optional<String> resolveRole(String rangerRole) {
        if (rangerRole == null) {
            LOG.warn("Attempted to resolve null role principal, returning empty");
            return Optional.empty();
        }
        LOG.warn("Identity Center does not support role principals; role '{}' will be skipped", rangerRole);
        if (metricsEmitter != null) {
            metricsEmitter.recordUnmappedPrincipal("role");
        }
        return Optional.empty();
    }
}
