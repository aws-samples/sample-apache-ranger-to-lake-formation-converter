package com.amazonaws.policyconverters.s3accessgrants;

import com.amazonaws.policyconverters.config.S3AccessGrantsConfig;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.AccessGrantsLocationConfiguration;
import software.amazon.awssdk.services.s3control.model.CreateAccessGrantRequest;
import software.amazon.awssdk.services.s3control.model.CreateAccessGrantResponse;
import software.amazon.awssdk.services.s3control.model.DeleteAccessGrantRequest;
import software.amazon.awssdk.services.s3control.model.DeleteAccessGrantResponse;
import software.amazon.awssdk.services.s3control.model.Grantee;
import software.amazon.awssdk.services.s3control.model.GranteeType;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsLocationsResponse;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse;
import software.amazon.awssdk.services.s3control.model.Permission;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for S3AccessGrantsClient.
 */
@ExtendWith(MockitoExtension.class)
class S3AccessGrantsClientTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String INSTANCE_ARN =
            "arn:aws:s3:us-east-1:123456789012:access-grants/default";
    private static final String PRINCIPAL_ARN = "arn:aws:iam::123456789012:user/alice";
    private static final String REGISTERED_LOCATION = "s3://my-bucket/";
    private static final String REGISTERED_LOCATION_ID = "loc-001";
    private static final String GRANT_PREFIX = "s3://my-bucket/data/";
    private static final String UNREGISTERED_PREFIX = "s3://other-bucket/data/";

    @Mock
    private S3ControlClient s3control;

    private S3AccessGrantsConfig config;
    private S3AccessGrantsClient client;
    private StringWriter deadLetterOutput;
    private DeadLetterLogger deadLetterLogger;

    @BeforeEach
    void setUp() {
        config = new S3AccessGrantsConfig(INSTANCE_ARN, ACCOUNT_ID);
        deadLetterOutput = new StringWriter();
        deadLetterLogger = new DeadLetterLogger(new BufferedWriter(deadLetterOutput));
        client = new S3AccessGrantsClient(config, s3control, deadLetterLogger);
    }

    // -----------------------------------------------------------------------
    // Test (a): GRANT with a registered location → CreateAccessGrant called
    // -----------------------------------------------------------------------

    @Test
    void createGrant_withRegisteredLocation_callsCreateAccessGrant() {
        // Stub listAccessGrantsLocations to return a registered location
        stubRegisteredLocations(REGISTERED_LOCATION, REGISTERED_LOCATION_ID);

        // Stub createAccessGrant to return a grant ID
        when(s3control.createAccessGrant(any(CreateAccessGrantRequest.class)))
                .thenReturn(CreateAccessGrantResponse.builder()
                        .accessGrantId("grant-001")
                        .build());

        S3AccessGrantOperation op = new S3AccessGrantOperation(
                OperationType.GRANT, PRINCIPAL_ARN, GRANT_PREFIX,
                S3AccessGrantPermission.READ, null);

        String grantId = client.createGrant(op);

        assertNotNull(grantId, "Grant ID should be returned for a registered location");
        assertEquals("grant-001", grantId);
        verify(s3control).createAccessGrant(any(CreateAccessGrantRequest.class));
    }

    @Test
    void createGrant_withRegisteredLocation_setsCorrectPrincipalAndPermission() {
        stubRegisteredLocations(REGISTERED_LOCATION, REGISTERED_LOCATION_ID);

        ArgumentCaptor<CreateAccessGrantRequest> captor =
                ArgumentCaptor.forClass(CreateAccessGrantRequest.class);
        when(s3control.createAccessGrant(captor.capture()))
                .thenReturn(CreateAccessGrantResponse.builder()
                        .accessGrantId("grant-001")
                        .build());

        S3AccessGrantOperation op = new S3AccessGrantOperation(
                OperationType.GRANT, PRINCIPAL_ARN, GRANT_PREFIX,
                S3AccessGrantPermission.READ, null);

        client.createGrant(op);

        CreateAccessGrantRequest req = captor.getValue();
        assertNotNull(req.grantee());
        assertEquals(GranteeType.IAM, req.grantee().granteeType());
        assertEquals(PRINCIPAL_ARN, req.grantee().granteeIdentifier());
        assertEquals(Permission.READ, req.permission());
        assertEquals(ACCOUNT_ID, req.accountId());
    }

    // -----------------------------------------------------------------------
    // Test (b): GRANT with unregistered location → CreateAccessGrant NOT called
    // -----------------------------------------------------------------------

    @Test
    void createGrant_withUnregisteredLocation_doesNotCallCreateAccessGrant() {
        stubRegisteredLocations(REGISTERED_LOCATION, REGISTERED_LOCATION_ID);

        S3AccessGrantOperation op = new S3AccessGrantOperation(
                OperationType.GRANT, PRINCIPAL_ARN, UNREGISTERED_PREFIX,
                S3AccessGrantPermission.READ, null);

        String grantId = client.createGrant(op);

        assertNull(grantId, "Grant ID should be null for an unregistered location");
        verify(s3control, never()).createAccessGrant(any(CreateAccessGrantRequest.class));
    }

    @Test
    void createGrant_withUnregisteredLocation_writesToDeadLetter() {
        stubRegisteredLocations(REGISTERED_LOCATION, REGISTERED_LOCATION_ID);

        S3AccessGrantOperation op = new S3AccessGrantOperation(
                OperationType.GRANT, PRINCIPAL_ARN, UNREGISTERED_PREFIX,
                S3AccessGrantPermission.READ, null);

        client.createGrant(op);

        String deadLetterContent = deadLetterOutput.toString();
        assertFalse(deadLetterContent.isBlank(), "Dead-letter log should have an entry");
        assertTrue(deadLetterContent.contains(UNREGISTERED_PREFIX),
                "Dead-letter entry should contain the unregistered prefix");
    }

    // -----------------------------------------------------------------------
    // Test (c): REVOKE → DeleteAccessGrant called with correct grantId
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void deleteGrant_callsDeleteAccessGrantWithCorrectGrantId() {
        String grantId = "grant-to-delete-001";

        // The implementation uses a Consumer lambda: s3control.deleteAccessGrant(r -> r.accountId(...).accessGrantId(...))
        // Mockito intercepts the Consumer overload; capture the built request via doAnswer.
        List<DeleteAccessGrantRequest> captured = new ArrayList<>();
        doAnswer(inv -> {
            Consumer<DeleteAccessGrantRequest.Builder> consumer = inv.getArgument(0);
            DeleteAccessGrantRequest.Builder builder = DeleteAccessGrantRequest.builder();
            consumer.accept(builder);
            captured.add(builder.build());
            return DeleteAccessGrantResponse.builder().build();
        }).when(s3control).deleteAccessGrant(any(Consumer.class));

        client.deleteGrant(grantId);

        verify(s3control).deleteAccessGrant(any(Consumer.class));
        assertEquals(1, captured.size(), "Expected exactly one deleteAccessGrant call");
        assertEquals(ACCOUNT_ID, captured.get(0).accountId());
        assertEquals(grantId, captured.get(0).accessGrantId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyBatch_revoke_resolvesGrantIdFromListGrants() {
        // Stub listAccessGrants to return an existing grant matching our revoke target
        String existingGrantId = "existing-grant-777";
        Grantee grantee = Grantee.builder()
                .granteeType(GranteeType.IAM)
                .granteeIdentifier(PRINCIPAL_ARN)
                .build();
        ListAccessGrantEntry existingEntry = ListAccessGrantEntry.builder()
                .accessGrantId(existingGrantId)
                .grantee(grantee)
                .permission(Permission.READ)
                .grantScope(GRANT_PREFIX)
                .build();
        ListAccessGrantsResponse listGrantsResponse = ListAccessGrantsResponse.builder()
                .accessGrantsList(List.of(existingEntry))
                .build();
        when(s3control.listAccessGrants(any(Consumer.class))).thenReturn(listGrantsResponse);

        // Capture what grantId is passed to deleteGrant via Consumer
        List<DeleteAccessGrantRequest> deleteCalls = new ArrayList<>();
        doAnswer(inv -> {
            Consumer<DeleteAccessGrantRequest.Builder> consumer = inv.getArgument(0);
            DeleteAccessGrantRequest.Builder builder = DeleteAccessGrantRequest.builder();
            consumer.accept(builder);
            deleteCalls.add(builder.build());
            return DeleteAccessGrantResponse.builder().build();
        }).when(s3control).deleteAccessGrant(any(Consumer.class));

        S3AccessGrantOperation revokeOp = new S3AccessGrantOperation(
                OperationType.REVOKE, PRINCIPAL_ARN, GRANT_PREFIX,
                S3AccessGrantPermission.READ, null);

        S3AccessGrantsClient.BatchResult result = client.applyBatch(
                List.of(revokeOp), 0, 100L);

        assertEquals(1, result.revokes(), "Expected one successful revoke");
        assertEquals(0, result.skipped(), "Expected no skipped operations");

        assertEquals(1, deleteCalls.size(), "Expected exactly one deleteAccessGrant call");
        assertEquals(existingGrantId, deleteCalls.get(0).accessGrantId(),
                "DeleteAccessGrant should use the grant ID resolved from listGrants()");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Stub listAccessGrantsLocations to return a single registered location.
     * The first call (no locationScope filter) returns the location entry.
     * The second call (with locationScope filter for resolveLocationId) also returns it.
     */
    @SuppressWarnings("unchecked")
    private void stubRegisteredLocations(String locationScope, String locationId) {
        ListAccessGrantsLocationsEntry entry = ListAccessGrantsLocationsEntry.builder()
                .locationScope(locationScope)
                .accessGrantsLocationId(locationId)
                .build();
        ListAccessGrantsLocationsResponse response = ListAccessGrantsLocationsResponse.builder()
                .accessGrantsLocationsList(List.of(entry))
                .build();
        when(s3control.listAccessGrantsLocations(any(Consumer.class))).thenReturn(response);
    }
}
