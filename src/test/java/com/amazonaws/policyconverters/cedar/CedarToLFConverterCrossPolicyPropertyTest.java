package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.reporting.GapReporter;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property: if any forbid exists for (principal, action, resource) with no matching
 * deny-exception, the output must contain zero grants for that triple.
 */
class CedarToLFConverterCrossPolicyPropertyTest {

    private static final String PRINCIPAL =
            "arn:aws:iam::123456789012:role/AnalystRole";
    private static final String TABLE_ARN =
            "arn:aws:glue:us-east-1:123456789012:table/testdb/orders";
    private static final String ACTION = "SELECT";

    private CedarToLFConverter converter =
            new CedarToLFConverter(new CedarSchemaProvider(), new GapReporter(), null);

    @BeforeEach
    void setUp() {
        converter = new CedarToLFConverter(new CedarSchemaProvider(), new GapReporter(), null);
    }

    @Property(tries = 100)
    void forbidWithoutDenyExceptionAlwaysProducesZeroGrantsForThatTriple(
            @ForAll @IntRange(min = 0, max = 5) int extraPermits
    ) throws Exception {
        StringBuilder cedar = new StringBuilder();
        cedar.append(String.format("""
                @source("forbid-policy")
                forbid(
                    principal == DataCatalog::Principal::"%s",
                    action == DataCatalog::Action::"%s",
                    resource == DataCatalog::Table::"%s"
                );
                """, PRINCIPAL, ACTION, TABLE_ARN));
        for (int i = 0; i < extraPermits; i++) {
            cedar.append(String.format("""
                    @source("permit-policy-%d")
                    permit(
                        principal == DataCatalog::Principal::"%s",
                        action == DataCatalog::Action::"%s",
                        resource == DataCatalog::Table::"%s"
                    );
                    """, i, PRINCIPAL, ACTION, TABLE_ARN));
        }

        CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedar.toString());
        List<LFPermissionOperation> ops = converter.convert(policySet);

        long grantsForForbiddenTriple = ops.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().equals(PRINCIPAL))
                .filter(op -> op.getPermissions().contains(LFPermission.SELECT))
                .filter(op -> "testdb".equals(op.getResource().getDatabaseName()))
                .filter(op -> "orders".equals(op.getResource().getTableName()))
                .count();

        assertEquals(0, grantsForForbiddenTriple,
                "A forbid with no deny-exception must suppress all permits for the same triple, " +
                "regardless of how many permit statements exist or which policies they come from. " +
                "extraPermits=" + extraPermits);
    }
}
