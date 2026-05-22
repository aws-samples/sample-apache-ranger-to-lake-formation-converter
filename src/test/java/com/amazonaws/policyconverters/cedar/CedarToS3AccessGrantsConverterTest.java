package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CedarToS3AccessGrantsConverter.
 *
 * <p>Cedar text uses DataCatalog::Action::"s3:..." for actions (as produced by
 * RangerToCedarConverter) and S3::Object:: for the resource entity type.
 */
class CedarToS3AccessGrantsConverterTest {

    private CedarToS3AccessGrantsConverter converter;

    private static final String PRINCIPAL = "arn:aws:iam::123456789012:user/alice";
    private static final String S3_ARN = "arn:aws:s3:::my-bucket/data/*";
    private static final String EXPECTED_PREFIX = "s3://my-bucket/data/*";

    @BeforeEach
    void setUp() {
        converter = new CedarToS3AccessGrantsConverter();
    }

    // --- Permission aggregation: READ ---

    @Test
    void getObjectAloneProducesRead() throws Exception {
        List<S3AccessGrantOperation> ops = convertSingle("s3:GetObject");
        assertSingleRead(ops);
    }

    @Test
    void listObjectsAloneProducesRead() throws Exception {
        List<S3AccessGrantOperation> ops = convertSingle("s3:ListObjects");
        assertSingleRead(ops);
    }

    @Test
    void getObjectAndListObjectsProducesRead() throws Exception {
        List<S3AccessGrantOperation> ops = convertMulti("s3:GetObject", "s3:ListObjects");
        assertEquals(1, ops.size(), "Expected one merged operation");
        assertEquals(S3AccessGrantPermission.READ, ops.get(0).permission());
    }

    // --- Permission aggregation: WRITE ---

    @Test
    void putObjectAloneProducesWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertSingle("s3:PutObject");
        assertSingleWrite(ops);
    }

    @Test
    void deleteObjectAloneProducesWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertSingle("s3:DeleteObject");
        assertSingleWrite(ops);
    }

    @Test
    void putObjectAndDeleteObjectProducesWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertMulti("s3:PutObject", "s3:DeleteObject");
        assertEquals(1, ops.size(), "Expected one merged operation");
        assertEquals(S3AccessGrantPermission.WRITE, ops.get(0).permission());
    }

    // --- Permission aggregation: READWRITE ---

    @Test
    void getObjectAndPutObjectProducesReadWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertMulti("s3:GetObject", "s3:PutObject");
        assertEquals(1, ops.size(), "Expected one merged operation");
        assertEquals(S3AccessGrantPermission.READWRITE, ops.get(0).permission());
    }

    @Test
    void listObjectsAndPutObjectProducesReadWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertMulti("s3:ListObjects", "s3:PutObject");
        assertEquals(1, ops.size(), "Expected one merged operation");
        assertEquals(S3AccessGrantPermission.READWRITE, ops.get(0).permission());
    }

    @Test
    void getObjectAndPutObjectAndDeleteObjectProducesReadWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertMulti(
                "s3:GetObject", "s3:PutObject", "s3:DeleteObject");
        assertEquals(1, ops.size(), "Expected one merged operation");
        assertEquals(S3AccessGrantPermission.READWRITE, ops.get(0).permission());
    }

    @Test
    void allFourActionsProduceReadWrite() throws Exception {
        List<S3AccessGrantOperation> ops = convertMulti(
                "s3:GetObject", "s3:ListObjects", "s3:PutObject", "s3:DeleteObject");
        assertEquals(1, ops.size(), "Expected one merged operation");
        assertEquals(S3AccessGrantPermission.READWRITE, ops.get(0).permission());
    }

    // --- Filtering: DataCatalog-namespace statements are ignored ---

    @Test
    void dataCatalogNamespaceStatementIsIgnored() throws Exception {
        String cedarText = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                    action == DataCatalog::Action::"SELECT",
                    resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/mydb/orders"
                );
                """;
        CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedarText);
        List<S3AccessGrantOperation> ops = converter.convert(policySet);
        assertEquals(0, ops.size(),
                "DataCatalog-namespace statements should produce zero S3AG operations");
    }

    // --- grantId is null ---

    @Test
    void grantIdIsNullInConverterOutput() throws Exception {
        List<S3AccessGrantOperation> ops = convertSingle("s3:GetObject");
        assertFalse(ops.isEmpty(), "Expected at least one operation");
        assertNull(ops.get(0).grantId(), "grantId must be null in converter output");
    }

    // --- s3Prefix format ---

    @Test
    void s3PrefixIsConvertedFromArnToS3Uri() throws Exception {
        List<S3AccessGrantOperation> ops = convertSingle("s3:GetObject");
        assertFalse(ops.isEmpty());
        assertEquals(EXPECTED_PREFIX, ops.get(0).s3Prefix(),
                "s3Prefix should be s3:// URI form of the ARN");
    }

    // --- Helpers ---

    /**
     * Convert a Cedar policy with a single S3 action for the shared principal/resource.
     */
    private List<S3AccessGrantOperation> convertSingle(String action) throws Exception {
        String cedarText = buildCedarText(action);
        CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedarText);
        return converter.convert(policySet);
    }

    /**
     * Convert multiple Cedar policies (one permit per action) for the same principal/resource,
     * expecting the converter to aggregate them into a single operation.
     */
    private List<S3AccessGrantOperation> convertMulti(String... actions) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String action : actions) {
            sb.append(buildCedarText(action));
            sb.append("\n");
        }
        CedarPolicySet policySet = CedarPolicySet.fromCedarString(sb.toString());
        return converter.convert(policySet);
    }

    /**
     * Build a Cedar permit statement for the given S3 action, shared principal, and resource.
     *
     * <p>Uses DataCatalog::Action:: prefix (as produced by RangerToCedarConverter) and
     * S3::Object:: resource entity type so that CedarToS3AccessGrantsConverter matches it.
     */
    private String buildCedarText(String s3Action) {
        return String.format("""
                permit(
                    principal == DataCatalog::Principal::"%s",
                    action == DataCatalog::Action::"%s",
                    resource == S3::Object::"%s"
                );
                """, PRINCIPAL, s3Action, S3_ARN);
    }

    private void assertSingleRead(List<S3AccessGrantOperation> ops) {
        assertEquals(1, ops.size(), "Expected exactly one operation");
        assertEquals(S3AccessGrantPermission.READ, ops.get(0).permission());
    }

    private void assertSingleWrite(List<S3AccessGrantOperation> ops) {
        assertEquals(1, ops.size(), "Expected exactly one operation");
        assertEquals(S3AccessGrantPermission.WRITE, ops.get(0).permission());
    }
}
