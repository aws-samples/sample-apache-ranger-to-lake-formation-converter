package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.AddLfTagsToResourceRequest;
import software.amazon.awssdk.services.lakeformation.model.AlreadyExistsException;
import software.amazon.awssdk.services.lakeformation.model.ColumnLFTag;
import software.amazon.awssdk.services.lakeformation.model.CreateLfTagRequest;
import software.amazon.awssdk.services.lakeformation.model.DeleteLfTagRequest;
import software.amazon.awssdk.services.lakeformation.model.EntityNotFoundException;
import software.amazon.awssdk.services.lakeformation.model.GetResourceLfTagsRequest;
import software.amazon.awssdk.services.lakeformation.model.GetResourceLfTagsResponse;
import software.amazon.awssdk.services.lakeformation.model.LFTagPair;
import software.amazon.awssdk.services.lakeformation.model.ListLfTagsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListLfTagsResponse;
import software.amazon.awssdk.services.lakeformation.model.RemoveLfTagsFromResourceRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LakeFormationClientTagTest {

    @Mock
    private software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient;

    private LakeFormationClient client;

    private static final String CATALOG_ID = "123456789012";

    @BeforeEach
    void setUp() {
        RetryConfig retryConfig = new RetryConfig(1, 0L, 1.0, 0L);
        client = new LakeFormationClient(awsClient, retryConfig, millis -> {});
    }

    // --- createLFTag ---

    @Test
    void createLFTag_sendsCorrectRequest() {
        client.createLFTag(CATALOG_ID, "PII", List.of("true"));

        ArgumentCaptor<CreateLfTagRequest> captor = ArgumentCaptor.forClass(CreateLfTagRequest.class);
        verify(awsClient).createLFTag(captor.capture());

        CreateLfTagRequest req = captor.getValue();
        assertEquals(CATALOG_ID, req.catalogId());
        assertEquals("PII", req.tagKey());
        assertEquals(List.of("true"), req.tagValues());
    }

    @Test
    void createLFTag_alreadyExistsException_doesNotThrow() {
        when(awsClient.createLFTag(any(CreateLfTagRequest.class)))
                .thenThrow(AlreadyExistsException.builder().message("already exists").build());
        assertDoesNotThrow(() -> client.createLFTag(CATALOG_ID, "PII", List.of("true")));
    }

    // --- deleteLFTag ---

    @Test
    void deleteLFTag_sendsCorrectRequest() {
        client.deleteLFTag(CATALOG_ID, "PII");

        ArgumentCaptor<DeleteLfTagRequest> captor = ArgumentCaptor.forClass(DeleteLfTagRequest.class);
        verify(awsClient).deleteLFTag(captor.capture());

        DeleteLfTagRequest req = captor.getValue();
        assertEquals(CATALOG_ID, req.catalogId());
        assertEquals("PII", req.tagKey());
    }

    @Test
    void deleteLFTag_entityNotFoundException_doesNotThrow() {
        when(awsClient.deleteLFTag(any(DeleteLfTagRequest.class)))
                .thenThrow(EntityNotFoundException.builder().message("not found").build());
        assertDoesNotThrow(() -> client.deleteLFTag(CATALOG_ID, "PII"));
    }

    // --- listLFTagKeys ---

    @Test
    void listLFTagKeys_singlePage() {
        ListLfTagsResponse response = ListLfTagsResponse.builder()
                .lfTags(
                        LFTagPair.builder().tagKey("PII").tagValues("true").build(),
                        LFTagPair.builder().tagKey("SENSITIVE").tagValues("true").build())
                .build();
        when(awsClient.listLFTags(any(ListLfTagsRequest.class))).thenReturn(response);

        List<String> keys = client.listLFTagKeys(CATALOG_ID);
        assertEquals(List.of("PII", "SENSITIVE"), keys);
    }

    @Test
    void listLFTagKeys_paginates() {
        ListLfTagsResponse page1 = ListLfTagsResponse.builder()
                .lfTags(LFTagPair.builder().tagKey("PII").tagValues("true").build())
                .nextToken("token-2")
                .build();
        ListLfTagsResponse page2 = ListLfTagsResponse.builder()
                .lfTags(LFTagPair.builder().tagKey("SENSITIVE").tagValues("true").build())
                .build();

        ArgumentCaptor<ListLfTagsRequest> captor = ArgumentCaptor.forClass(ListLfTagsRequest.class);
        when(awsClient.listLFTags(captor.capture())).thenReturn(page1, page2);

        List<String> keys = client.listLFTagKeys(CATALOG_ID);
        assertEquals(2, keys.size());
        assertTrue(keys.contains("PII"));
        assertTrue(keys.contains("SENSITIVE"));

        // Second call should include the nextToken
        List<ListLfTagsRequest> requests = captor.getAllValues();
        assertEquals(2, requests.size());
        assertEquals("token-2", requests.get(1).nextToken());
    }

    @Test
    void listLFTagKeys_emptyList() {
        when(awsClient.listLFTags(any(ListLfTagsRequest.class)))
                .thenReturn(ListLfTagsResponse.builder().build());
        assertTrue(client.listLFTagKeys(CATALOG_ID).isEmpty());
    }

    // --- getResourceLFTags ---

    @Test
    void getResourceLFTags_database_collectsDatabaseTags() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", null, null, null);
        GetResourceLfTagsResponse response = GetResourceLfTagsResponse.builder()
                .lfTagOnDatabase(
                        LFTagPair.builder().tagKey("PII").tagValues("true").build())
                .build();
        when(awsClient.getResourceLFTags(any(GetResourceLfTagsRequest.class))).thenReturn(response);

        Map<String, String> tags = client.getResourceLFTags(resource, CATALOG_ID);
        assertEquals(Map.of("PII", "true"), tags);
    }

    @Test
    void getResourceLFTags_table_collectsTableTags() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", "mytable", null, null);
        GetResourceLfTagsResponse response = GetResourceLfTagsResponse.builder()
                .lfTagsOnTable(
                        LFTagPair.builder().tagKey("SENSITIVE").tagValues("true").build())
                .build();
        when(awsClient.getResourceLFTags(any(GetResourceLfTagsRequest.class))).thenReturn(response);

        Map<String, String> tags = client.getResourceLFTags(resource, CATALOG_ID);
        assertEquals(Map.of("SENSITIVE", "true"), tags);
    }

    @Test
    void getResourceLFTags_column_collectsColumnTags() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", "mytable",
                java.util.Set.of("col1"), null);
        ColumnLFTag columnTag = ColumnLFTag.builder()
                .name("col1")
                .lfTags(LFTagPair.builder().tagKey("PII").tagValues("true").build())
                .build();
        GetResourceLfTagsResponse response = GetResourceLfTagsResponse.builder()
                .lfTagsOnColumns(columnTag)
                .build();
        when(awsClient.getResourceLFTags(any(GetResourceLfTagsRequest.class))).thenReturn(response);

        Map<String, String> tags = client.getResourceLFTags(resource, CATALOG_ID);
        assertEquals(Map.of("PII", "true"), tags);
    }

    @Test
    void getResourceLFTags_propagatesException() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", null, null, null);
        when(awsClient.getResourceLFTags(any(GetResourceLfTagsRequest.class)))
                .thenThrow(EntityNotFoundException.builder().message("not found").build());

        assertThrows(EntityNotFoundException.class,
                () -> client.getResourceLFTags(resource, CATALOG_ID));
    }

    // --- addLFTagsToResource ---

    @Test
    void addLFTagsToResource_sendsCorrectRequest() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", "mytable", null, null);
        Map<String, String> tags = Map.of("PII", "true");

        client.addLFTagsToResource(resource, tags, CATALOG_ID);

        ArgumentCaptor<AddLfTagsToResourceRequest> captor =
                ArgumentCaptor.forClass(AddLfTagsToResourceRequest.class);
        verify(awsClient).addLFTagsToResource(captor.capture());

        AddLfTagsToResourceRequest req = captor.getValue();
        assertEquals(CATALOG_ID, req.catalogId());
        assertEquals(1, req.lfTags().size());
        assertEquals("PII", req.lfTags().get(0).tagKey());
        assertEquals(List.of("true"), req.lfTags().get(0).tagValues());
    }

    // --- removeLFTagsFromResource ---

    @Test
    void removeLFTagsFromResource_sendsCorrectRequest() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", "mytable", null, null);

        client.removeLFTagsFromResource(resource, List.of("PII"), CATALOG_ID);

        ArgumentCaptor<RemoveLfTagsFromResourceRequest> captor =
                ArgumentCaptor.forClass(RemoveLfTagsFromResourceRequest.class);
        verify(awsClient).removeLFTagsFromResource(captor.capture());

        RemoveLfTagsFromResourceRequest req = captor.getValue();
        assertEquals(CATALOG_ID, req.catalogId());
        assertEquals(1, req.lfTags().size());
        assertEquals("PII", req.lfTags().get(0).tagKey());
        assertEquals(List.of("true"), req.lfTags().get(0).tagValues());
    }

    @Test
    void removeLFTagsFromResource_multipleKeys() {
        LFResource resource = new LFResource(CATALOG_ID, "mydb", null, null, null);

        client.removeLFTagsFromResource(resource, List.of("PII", "SENSITIVE"), CATALOG_ID);

        ArgumentCaptor<RemoveLfTagsFromResourceRequest> captor =
                ArgumentCaptor.forClass(RemoveLfTagsFromResourceRequest.class);
        verify(awsClient).removeLFTagsFromResource(captor.capture());

        assertEquals(2, captor.getValue().lfTags().size());
    }
}
