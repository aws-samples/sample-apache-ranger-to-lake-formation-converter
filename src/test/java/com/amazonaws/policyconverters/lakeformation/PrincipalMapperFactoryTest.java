package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.IdentityCenterConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.amazonaws.policyconverters.lakeformation.CompositePrincipalMapper;

class PrincipalMapperFactoryTest {

    private static IdentityCenterConfig validIdcConfig() {
        return new IdentityCenterConfig("d-test123", "us-east-1", "123456789012", 60);
    }

    // --- STATIC type ---

    @Test
    void create_staticType_returnsStaticPrincipalMapper() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);

        PrincipalMapper result = PrincipalMapperFactory.create(config, null, null);

        assertInstanceOf(StaticPrincipalMapper.class, result);
    }

    // --- IDENTITY_CENTER type ---

    @Test
    void create_identityCenterType_returnsIdentityCenterPrincipalMapper() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());
        IdentitystoreClient identitystoreClient = mock(IdentitystoreClient.class);

        PrincipalMapper result = PrincipalMapperFactory.create(config, identitystoreClient, null);

        assertInstanceOf(IdentityCenterPrincipalMapper.class, result);
    }

    // --- null config throws ---

    @Test
    void create_nullConfig_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(null, null, null));
    }

    // --- IDENTITY_CENTER validation: null idcConfig ---

    @Test
    void create_identityCenter_nullIdcConfig_throwsIllegalArgument() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(config, mock(IdentitystoreClient.class), null));
        assertTrue(ex.getMessage().contains("idcConfig is required"),
                "Expected message to contain 'idcConfig is required', got: " + ex.getMessage());
    }

    // --- IDENTITY_CENTER validation: blank identityStoreId ---

    @Test
    void create_identityCenter_blankIdentityStoreId_throwsIllegalArgument() {
        IdentityCenterConfig idcConfig = new IdentityCenterConfig("  ", "us-east-1", "123456789012", 60);
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, idcConfig);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(config, mock(IdentitystoreClient.class), null));
        assertTrue(ex.getMessage().contains("identityStoreId"),
                "Expected message to contain 'identityStoreId', got: " + ex.getMessage());
    }

    // --- IDENTITY_CENTER validation: blank region ---

    @Test
    void create_identityCenter_blankRegion_throwsIllegalArgument() {
        IdentityCenterConfig idcConfig = new IdentityCenterConfig("d-test123", "  ", "123456789012", 60);
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, idcConfig);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(config, mock(IdentitystoreClient.class), null));
        assertTrue(ex.getMessage().contains("region"),
                "Expected message to contain 'region', got: " + ex.getMessage());
    }

    // --- IDENTITY_CENTER validation: blank accountId ---

    @Test
    void create_identityCenter_blankAccountId_throwsIllegalArgument() {
        IdentityCenterConfig idcConfig = new IdentityCenterConfig("d-test123", "us-east-1", "  ", 60);
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, idcConfig);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(config, mock(IdentitystoreClient.class), null));
        assertTrue(ex.getMessage().contains("accountId"),
                "Expected message to contain 'accountId', got: " + ex.getMessage());
    }

    // --- IDENTITY_CENTER validation: null identityStoreClient ---

    @Test
    void create_identityCenter_nullIdentityStoreClient_throwsIllegalArgument() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                null, null, null, PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(config, null, null));
        assertTrue(ex.getMessage().contains("identityStoreClient"),
                "Expected message to contain 'identityStoreClient', got: " + ex.getMessage());
    }

    // --- COMPOSITE type ---

    @Test
    void create_compositeType_buildsChainInOrder() {
        IdentitystoreClient idcClient = mock(IdentitystoreClient.class);

        PrincipalMappingConfig staticDelegate = new PrincipalMappingConfig(
                Map.of("alice", "arn:aws:iam::123:role/alice"),
                Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.STATIC, null);

        PrincipalMappingConfig idcDelegate = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());

        PrincipalMappingConfig composite = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.COMPOSITE, null,
                List.of(staticDelegate, idcDelegate));

        PrincipalMapper result = PrincipalMapperFactory.create(composite, idcClient, null);
        assertInstanceOf(CompositePrincipalMapper.class, result);
    }

    @Test
    void create_compositeType_emptyDelegates_throwsIllegalArgument() {
        PrincipalMappingConfig composite = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.COMPOSITE, null, Collections.emptyList());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(composite, null, null));
        assertTrue(ex.getMessage().contains("delegates"));
    }

    @Test
    void create_compositeType_nestedCompositeDelegate_throwsIllegalArgument() {
        PrincipalMappingConfig innerComposite = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.COMPOSITE, null,
                List.of(new PrincipalMappingConfig(null, null, null)));

        PrincipalMappingConfig outer = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.COMPOSITE, null, List.of(innerComposite));

        assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(outer, null, null));
    }

    @Test
    void create_compositeType_multipleIdcDelegates_throwsIllegalArgument() {
        IdentitystoreClient idcClient = mock(IdentitystoreClient.class);

        PrincipalMappingConfig idc1 = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());
        PrincipalMappingConfig idc2 = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());

        PrincipalMappingConfig composite = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.COMPOSITE, null, List.of(idc1, idc2));

        assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(composite, idcClient, null));
    }

    @Test
    void create_staticType_hasExplicitBranch_returnsStaticMapper() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null,
                PrincipalMapperType.STATIC, null);
        PrincipalMapper result = PrincipalMapperFactory.create(config, null, null);
        assertInstanceOf(StaticPrincipalMapper.class, result);
    }

    @Test
    void create_compositeType_idcDelegateWithNullIdcConfig_throwsIllegalArgument() {
        PrincipalMappingConfig idcNoConfig = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.IDENTITY_CENTER, null);
        PrincipalMappingConfig composite = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                PrincipalMapperType.COMPOSITE, null, List.of(idcNoConfig));
        assertThrows(IllegalArgumentException.class,
                () -> PrincipalMapperFactory.create(composite, mock(IdentitystoreClient.class), null));
    }
}
