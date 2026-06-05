package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.IdentityCenterConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;

import java.util.ArrayList;
import java.util.List;

public class PrincipalMapperFactory {

    private PrincipalMapperFactory() { }

    public static PrincipalMapper create(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {

        if (config == null)
            throw new IllegalArgumentException("PrincipalMappingConfig must not be null");

        PrincipalMapperType type = config.getType();

        if (type == PrincipalMapperType.STATIC) {
            return StaticPrincipalMapper.fromConfig(config, metricsEmitter);
        }

        if (type == PrincipalMapperType.IDENTITY_CENTER) {
            return buildIdentityCenter(config, identityStoreClient, metricsEmitter);
        }

        if (type == PrincipalMapperType.COMPOSITE) {
            return buildComposite(config, identityStoreClient, metricsEmitter);
        }

        throw new IllegalArgumentException(
                "Unknown PrincipalMapperType: " + type
                + ". Supported values: STATIC, IDENTITY_CENTER, COMPOSITE");
    }

    private static PrincipalMapper buildIdentityCenter(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {
        IdentityCenterConfig idcConfig = config.getIdcConfig();
        if (idcConfig == null)
            throw new IllegalArgumentException("idcConfig is required when type is IDENTITY_CENTER");
        if (idcConfig.getIdentityStoreId() == null || idcConfig.getIdentityStoreId().isBlank())
            throw new IllegalArgumentException("idcConfig.identityStoreId must not be blank");
        if (idcConfig.getRegion() == null || idcConfig.getRegion().isBlank())
            throw new IllegalArgumentException("idcConfig.region must not be blank");
        if (idcConfig.getAccountId() == null || idcConfig.getAccountId().isBlank())
            throw new IllegalArgumentException("idcConfig.accountId must not be blank");
        if (identityStoreClient == null)
            throw new IllegalArgumentException(
                    "identityStoreClient must not be null when type is IDENTITY_CENTER");
        return new IdentityCenterPrincipalMapper(idcConfig, identityStoreClient, metricsEmitter);
    }

    private static PrincipalMapper buildComposite(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {
        List<PrincipalMappingConfig> delegateConfigs = config.getDelegates();
        if (delegateConfigs == null || delegateConfigs.isEmpty())
            throw new IllegalArgumentException(
                    "COMPOSITE mapper requires at least one entry in 'delegates'");

        long idcCount = delegateConfigs.stream()
                .filter(d -> d.getType() == PrincipalMapperType.IDENTITY_CENTER)
                .count();
        if (idcCount > 1)
            throw new IllegalArgumentException(
                    "COMPOSITE mapper may contain at most one IDENTITY_CENTER delegate; found " + idcCount);

        for (PrincipalMappingConfig d : delegateConfigs) {
            if (d.getType() == PrincipalMapperType.COMPOSITE)
                throw new IllegalArgumentException(
                        "COMPOSITE mapper delegates must not themselves be COMPOSITE (no nesting)");
        }

        // Build delegate instances. Pass null MetricsEmitter so delegates never emit
        // intermediate-miss metrics — CompositePrincipalMapper is the single emitter.
        List<PrincipalMapper> delegates = new ArrayList<>();
        for (PrincipalMappingConfig delegateConfig : delegateConfigs) {
            delegates.add(create(delegateConfig, identityStoreClient, null));
        }
        return new CompositePrincipalMapper(delegates, metricsEmitter);
    }
}
