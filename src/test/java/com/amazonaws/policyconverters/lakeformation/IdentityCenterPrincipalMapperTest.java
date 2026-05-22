package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.IdentityCenterConfig;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;
import software.amazon.awssdk.services.identitystore.model.GetGroupIdRequest;
import software.amazon.awssdk.services.identitystore.model.GetGroupIdResponse;
import software.amazon.awssdk.services.identitystore.model.GetUserIdRequest;
import software.amazon.awssdk.services.identitystore.model.GetUserIdResponse;
import software.amazon.awssdk.services.identitystore.model.ResourceNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityCenterPrincipalMapperTest {

    @Mock
    private IdentitystoreClient identityStoreClient;

    @Mock
    private MetricsEmitter metricsEmitter;

    private static IdentityCenterConfig testConfig(int ttlMinutes) {
        return new IdentityCenterConfig("d-test123", "us-east-1", "123456789012", ttlMinutes);
    }

    private IdentityCenterPrincipalMapper mapper() {
        return new IdentityCenterPrincipalMapper(testConfig(60), identityStoreClient, metricsEmitter);
    }

    // --- resolveUser happy path ---

    @Test
    void resolveUser_happyPath_returnsCorrectArn() {
        when(identityStoreClient.getUserId(any(GetUserIdRequest.class)))
                .thenReturn(GetUserIdResponse.builder()
                        .userId("b4e81438-b041-70fe-61ba-665688ea3f47")
                        .build());

        Optional<String> result = mapper().resolveUser("alice");

        assertEquals(
                Optional.of("arn:aws:identitystore::123456789012:user/b4e81438-b041-70fe-61ba-665688ea3f47"),
                result);
        verifyNoInteractions(metricsEmitter);
    }

    // --- resolveGroup happy path ---

    @Test
    void resolveGroup_happyPath_returnsCorrectArn() {
        when(identityStoreClient.getGroupId(any(GetGroupIdRequest.class)))
                .thenReturn(GetGroupIdResponse.builder()
                        .groupId("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                        .build());

        Optional<String> result = mapper().resolveGroup("analysts");

        assertEquals(
                Optional.of("arn:aws:identitystore::123456789012:group/a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
                result);
        verifyNoInteractions(metricsEmitter);
    }

    // --- resolveUser not found ---

    @Test
    void resolveUser_notFound_returnsEmptyAndEmitsMetric() {
        when(identityStoreClient.getUserId(any(GetUserIdRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        Optional<String> result = mapper().resolveUser("alice");

        assertEquals(Optional.empty(), result);
        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("user");
    }

    // --- resolveGroup not found ---

    @Test
    void resolveGroup_notFound_returnsEmptyAndEmitsMetric() {
        when(identityStoreClient.getGroupId(any(GetGroupIdRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        Optional<String> result = mapper().resolveGroup("analysts");

        assertEquals(Optional.empty(), result);
        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("group");
    }

    // --- resolveUser SDK exception ---

    @Test
    void resolveUser_sdkException_returnsEmptyAndEmitsMetric() {
        when(identityStoreClient.getUserId(any(GetUserIdRequest.class)))
                .thenThrow(SdkException.builder().message("network error").build());

        Optional<String> result = mapper().resolveUser("alice");

        assertEquals(Optional.empty(), result);
        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("user");
    }

    // --- resolveRole always returns empty ---

    @Test
    void resolveRole_alwaysReturnsEmpty() {
        Optional<String> result = mapper().resolveRole("some_role");

        assertEquals(Optional.empty(), result);
        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("role");
        verifyNoInteractions(identityStoreClient);
    }

    // --- null input guards ---

    @Test
    void resolveUser_nullInput_returnsEmpty_noApiCall_noMetric() {
        Optional<String> result = mapper().resolveUser(null);

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(identityStoreClient);
        verifyNoInteractions(metricsEmitter);
    }

    @Test
    void resolveRole_nullInput_returnsEmpty_noMetric() {
        Optional<String> result = mapper().resolveRole(null);

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(metricsEmitter);
    }

    // --- cache hit: second call must not re-invoke the API ---

    @Test
    void resolveUser_cacheHit_doesNotCallApi() {
        when(identityStoreClient.getUserId(any(GetUserIdRequest.class)))
                .thenReturn(GetUserIdResponse.builder()
                        .userId("b4e81438-b041-70fe-61ba-665688ea3f47")
                        .build());

        IdentityCenterPrincipalMapper m = mapper();
        m.resolveUser("alice");
        m.resolveUser("alice");

        verify(identityStoreClient, times(1)).getUserId(any(GetUserIdRequest.class));
    }

    // --- cache expiry: TTL=0 means every call should hit the API ---

    @Test
    void resolveUser_cacheExpiry_callsApiAgain() {
        when(identityStoreClient.getUserId(any(GetUserIdRequest.class)))
                .thenReturn(GetUserIdResponse.builder()
                        .userId("b4e81438-b041-70fe-61ba-665688ea3f47")
                        .build());

        IdentityCenterPrincipalMapper m =
                new IdentityCenterPrincipalMapper(testConfig(0), identityStoreClient, metricsEmitter);
        m.resolveUser("alice");
        m.resolveUser("alice");

        verify(identityStoreClient, times(2)).getUserId(any(GetUserIdRequest.class));
    }
}
