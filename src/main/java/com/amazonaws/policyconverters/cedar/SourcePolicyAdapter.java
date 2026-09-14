package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.apache.ranger.plugin.model.RangerPolicy;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * Maps a specific policy source's access types and resource hierarchy
 * into Cedar actions and DataCatalog:: entity types.
 *
 * <p>Each implementation handles a single service type (e.g., "lakeformation", "trino").
 * The {@link com.amazonaws.policyconverters.ranger.RangerToCedarConverter} delegates
 * service-type-specific mapping to the adapter registered for the incoming policy's service type.
 */
public interface SourcePolicyAdapter {

    /**
     * The service type this adapter handles (e.g., "lakeformation", "trino").
     */
    String getServiceType();

    /**
     * Map a source access type to one or more Cedar action names.
     * Returns empty set if the access type is not mappable.
     */
    Set<String> mapAccessTypeToCedarActions(String sourceAccessType);

    /**
     * Map a source access type to Cedar actions, with awareness of the resource level.
     * The default delegates to the single-arg overload; adapters that vary behaviour
     * by resource level (e.g., EMR Spark url vs catalog) override this method.
     */
    default Set<String> mapAccessTypeToCedarActions(String sourceAccessType, String resourceLevel) {
        return mapAccessTypeToCedarActions(sourceAccessType);
    }

    /**
     * Access types this adapter recognises but deliberately does not map, because the
     * capability has no Lake Formation equivalent — e.g. Hive {@code index}/{@code lock},
     * Presto/Trino {@code grant}/{@code revoke}.
     *
     * <p><strong>This is not the same as an access type the adapter has never heard of.</strong>
     * An unrecognised type means the adapter itself has a coverage gap (a new Ranger release,
     * a custom access type) and must stay loud as an actionable {@code UNSUPPORTED_ACTION}.
     * A type listed here means "we know exactly what this is and it can never map", and is
     * reported as an informational {@code KNOWN_UNMAPPABLE_ACTION} that does not affect a
     * policy's convertibility classification.
     *
     * <p>Values must be lower-case; matching is done against a normalized input.
     * Defaults to empty — adapters with no such types need not override.
     */
    default Set<String> getKnownUnmappableAccessTypes() {
        return Collections.emptySet();
    }

    /**
     * Whether {@code sourceAccessType} is one this adapter deliberately does not map.
     * Normalizes the input, because callers pass the raw value straight from the Ranger
     * policy JSON. Adapters override {@link #getKnownUnmappableAccessTypes()}, not this.
     */
    default boolean isKnownUnmappableAccessType(String sourceAccessType) {
        if (sourceAccessType == null) {
            return false;
        }
        return getKnownUnmappableAccessTypes()
                .contains(sourceAccessType.trim().toLowerCase());
    }

    /**
     * Build a Cedar entity identifier for the given resource.
     * Returns the entity type (e.g., "DataCatalog::Database") and
     * the identifier string (e.g., an ARN or URN).
     */
    CedarEntityRef buildEntityRef(RangerPolicy policy, String resourceLevel);

    /**
     * Build a Cedar principal identifier from a resolved IAM ARN
     * or other identity string.
     */
    String buildPrincipalRef(String resolvedPrincipalId);

    /**
     * Return the AWS region and account ID for ARN construction,
     * or empty if not applicable (non-AWS sources).
     */
    Optional<AwsContext> getAwsContext();

    /**
     * Whether this adapter should process the given policy.
     * Returns {@code true} by default. Catalog-aware adapters (Presto, Trino)
     * override this to filter policies by their configured GDC catalog name.
     */
    default boolean shouldProcessPolicy(RangerPolicy policy) {
        return true;
    }

    /**
     * Detect a service-specific resource key that this adapter recognises by name but that has
     * no Lake Formation equivalent at all — e.g. Trino/Presto {@code procedure}, {@code function},
     * {@code trinouser}, {@code sessionproperty}, {@code systemproperty}. Unlike an unmappable
     * access type, these can't even be assigned a resource level (there is no LF resource shaped
     * like a "procedure"), so they must be caught before {@code determineResourceLevel} falls
     * through to "database" and silently produces a wrong-scoped grant.
     *
     * <p>Returns the offending resource key so the caller can build an actionable gap message.
     * Kept off the shared resource-level-detection path in
     * {@code RangerToCedarConverter#determineResourceLevel} deliberately: those keys are specific
     * to one or two service types and don't belong in code shared by every adapter.
     *
     * <p>Defaults to empty — adapters with no such resource keys need not override.
     */
    default Optional<String> detectUnmappableResourceType(RangerPolicy policy) {
        return Optional.empty();
    }
}
