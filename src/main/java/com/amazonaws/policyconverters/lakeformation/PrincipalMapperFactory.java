package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.IdentityCenterConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;

/**
 * Factory for creating {@link PrincipalMapper} instances based on a {@link PrincipalMappingConfig}.
 *
 * <p>Supported mapper types:
 * <ul>
 *   <li>{@link PrincipalMapperType#STATIC} — delegates to {@link StaticPrincipalMapper}</li>
 *   <li>{@link PrincipalMapperType#IDENTITY_CENTER} — delegates to {@link IdentityCenterPrincipalMapper}</li>
 * </ul>
 */
public class PrincipalMapperFactory {

    private PrincipalMapperFactory() {
        // utility class — no instances
    }

    /**
     * Create a {@link PrincipalMapper} from the supplied configuration.
     *
     * @param config              the principal mapping configuration; must not be {@code null}
     * @param identityStoreClient the AWS Identity Store client; only required when
     *                            {@code config.getType() == IDENTITY_CENTER}, may be {@code null} otherwise
     * @param metricsEmitter      optional emitter for recording metrics; may be {@code null}
     * @return a configured {@link PrincipalMapper} instance
     * @throws IllegalArgumentException if {@code config} is {@code null}, or if required
     *                                  IDENTITY_CENTER fields are missing
     */
    public static PrincipalMapper create(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {

        if (config == null) {
            throw new IllegalArgumentException("PrincipalMappingConfig must not be null");
        }

        if (config.getType() == PrincipalMapperType.IDENTITY_CENTER) {
            IdentityCenterConfig idcConfig = config.getIdcConfig();
            if (idcConfig == null) {
                throw new IllegalArgumentException(
                        "idcConfig is required when type is IDENTITY_CENTER");
            }
            if (idcConfig.getIdentityStoreId() == null || idcConfig.getIdentityStoreId().isBlank()) {
                throw new IllegalArgumentException(
                        "idcConfig.identityStoreId must not be blank");
            }
            if (idcConfig.getRegion() == null || idcConfig.getRegion().isBlank()) {
                throw new IllegalArgumentException(
                        "idcConfig.region must not be blank");
            }
            if (idcConfig.getAccountId() == null || idcConfig.getAccountId().isBlank()) {
                throw new IllegalArgumentException(
                        "idcConfig.accountId must not be blank");
            }
            if (identityStoreClient == null) {
                throw new IllegalArgumentException(
                        "identityStoreClient must not be null when type is IDENTITY_CENTER");
            }
            return new IdentityCenterPrincipalMapper(idcConfig, identityStoreClient, metricsEmitter);
        }

        // STATIC (or any other / null type — belt-and-suspenders since PrincipalMappingConfig
        // already coerces null to STATIC in its constructor)
        return StaticPrincipalMapper.fromConfig(config, metricsEmitter);
    }
}
