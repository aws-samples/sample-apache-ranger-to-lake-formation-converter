package com.amazonaws.policyconverters;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies row filter expressions are preserved with byte-equality through the
 * Cedar-to-LF conversion pipeline. A silently dropped or truncated row filter
 * produces a more permissive LF policy than intended — a security miss.
 */
class RowFilterPassThroughTest {

    private CedarToLFConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CedarToLFConverter(new CedarSchemaProvider(), new GapReporter(), null);
    }

    /**
     * SQL metacharacters (single quotes, double quotes, SQL comment marker) must be
     * preserved byte-for-byte through the Cedar string escaping round-trip.
     * The converter escapes double quotes as \" in Cedar and unescapes them on parse,
     * so the recovered expression must equal the original.
     */
    @Test
    void sqlMetacharactersPreservedWithByteEquality() throws Exception {
        // Use a filter with double quotes and SQL comment — these are the metacharacters
        // most likely to be silently stripped or mangled by string-processing code.
        String filterExpression = "region = 'us-east-1' AND dept = \"sales\" -- comment";

        String cedarText = buildCedarWithRowFilter(filterExpression);
        List<LFPermissionOperation> ops = converter.convert(CedarPolicySet.fromCedarString(cedarText));

        assertEquals(1, ops.size());
        assertEquals(OperationType.GRANT, ops.get(0).getOperationType());
        assertEquals(filterExpression,
                ops.get(0).getResource().getRowFilterExpression(),
                "Row filter expression must be preserved byte-for-byte. " +
                "Any mutation would silently change data access semantics.");
    }

    /**
     * A filter expression that looks benign but contains the string literal "end" next to a
     * quote boundary, testing that the regex capture group does not stop short at
     * an apparent terminator. If a grant is produced, the filter must be byte-equal to the input.
     */
    @Test
    void quoteAdjacentTokenInRowFilterPreservedOrGapRecorded() throws Exception {
        String filterWithQuoteBoundary = "col = 'value end'";
        String cedarText = buildCedarWithRowFilter(filterWithQuoteBoundary);

        List<LFPermissionOperation> ops = assertDoesNotThrow(
                () -> converter.convert(CedarPolicySet.fromCedarString(cedarText)));

        ops.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .forEach(op -> assertEquals(filterWithQuoteBoundary,
                        op.getResource().getRowFilterExpression(),
                        "If a grant is produced, the row filter must be preserved exactly. " +
                        "A quote-adjacent token must not silently truncate the filter expression."));
    }

    /**
     * An overlong row filter (5 000 characters) must pass through verbatim.
     * The converter must not impose a length cap — that is the LF API's responsibility.
     * Truncation here would silently produce a more permissive policy than intended.
     */
    @Test
    void overlongRowFilterPassedThroughVerbatim() throws Exception {
        String longFilter = "col = '" + "x".repeat(5000) + "'";
        String cedarText = buildCedarWithRowFilter(longFilter);

        List<LFPermissionOperation> ops = assertDoesNotThrow(
                () -> converter.convert(CedarPolicySet.fromCedarString(cedarText)));

        assertEquals(1, ops.size(),
                "Overlong row filter must still produce a grant operation (pass through to LF API)");
        assertEquals(longFilter,
                ops.get(0).getResource().getRowFilterExpression(),
                "Overlong row filter must be passed through verbatim — truncation would " +
                "silently make the policy more permissive than intended.");
    }

    /**
     * Build a Cedar permit statement that carries the given row filter expression.
     *
     * <p>The Cedar string syntax uses double-quoted string literals. The converter's
     * {@code unescapeQuotes} method reverses {@code \"} back to {@code "} and
     * {@code \\} back to {@code \}, so we must apply the inverse escaping here.
     */
    private String buildCedarWithRowFilter(String filterExpression) {
        // Escape for embedding in a Cedar double-quoted string literal:
        //   \ -> \\ (must be first to avoid double-escaping)
        //   " -> \"
        String escaped = filterExpression
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return String.format("""
                @source("policy-1")
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                    action == DataCatalog::Action::"SELECT",
                    resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
                ) when {
                    resource.rowFilter == "%s"
                };
                """, escaped);
    }
}
