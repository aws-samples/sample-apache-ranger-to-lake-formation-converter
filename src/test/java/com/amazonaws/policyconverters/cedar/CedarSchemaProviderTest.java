package com.amazonaws.policyconverters.cedar;

import com.cedarpolicy.model.schema.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CedarSchemaProviderTest {

    private static CedarSchemaProvider provider;

    @BeforeAll
    static void setUp() {
        provider = new CedarSchemaProvider();
    }

    // --- Schema loading ---

    @Test
    void schemaLoadsSuccessfully() {
        Schema schema = provider.getSchema();
        assertNotNull(schema, "Schema should be loaded from classpath");
    }

    // --- Valid policy validation ---

    @Test
    void validSelectPolicyPassesValidation() {
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
                    action == DataCatalog::Action::"SELECT",
                    resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/db/orders"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertTrue(errors.isEmpty(), "Valid SELECT policy should pass validation, but got: " + errors);
    }

    @Test
    void validDescribePolicyOnDatabasePassesValidation() {
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
                    action == DataCatalog::Action::"DESCRIBE",
                    resource == DataCatalog::Database::"arn:aws:glue:us-east-1:123456789012:database/mydb"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertTrue(errors.isEmpty(), "Valid DESCRIBE on Database should pass validation, but got: " + errors);
    }

    @Test
    void validForbidPolicyPassesValidation() {
        String policy = """
                forbid(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
                    action == DataCatalog::Action::"SELECT",
                    resource == DataCatalog::Column::"arn:aws:glue:us-east-1:123456789012:column/db/orders/email"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertTrue(errors.isEmpty(), "Valid forbid policy should pass validation, but got: " + errors);
    }

    @Test
    void validDataLocationAccessPolicyPassesValidation() {
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Admin",
                    action == DataCatalog::Action::"DATA_LOCATION_ACCESS",
                    resource == DataCatalog::DataLocation::"arn:aws:s3:::my-bucket/data/path"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertTrue(errors.isEmpty(), "Valid DATA_LOCATION_ACCESS policy should pass validation, but got: " + errors);
    }

    @Test
    void validCreateDatabasePolicyOnCatalogPassesValidation() {
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Admin",
                    action == DataCatalog::Action::"CREATE_DATABASE",
                    resource == DataCatalog::Catalog::"arn:aws:glue:us-east-1:123456789012:catalog"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertTrue(errors.isEmpty(), "Valid CREATE_DATABASE on Catalog should pass validation, but got: " + errors);
    }

    // --- Invalid policy validation ---

    @Test
    void policyWithUnknownEntityTypeFailsValidation() {
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
                    action == DataCatalog::Action::"SELECT",
                    resource == DataCatalog::UnknownType::"some-id"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertFalse(errors.isEmpty(), "Policy with unknown entity type should fail validation");
    }

    @Test
    void policyWithUnknownActionFailsValidation() {
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
                    action == DataCatalog::Action::"NONEXISTENT_ACTION",
                    resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/db/orders"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertFalse(errors.isEmpty(), "Policy with unknown action should fail validation");
    }

    @Test
    void policyWithWrongResourceTypeForActionFailsValidation() {
        // INSERT only applies to Table, not Database
        String policy = """
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
                    action == DataCatalog::Action::"INSERT",
                    resource == DataCatalog::Database::"arn:aws:glue:us-east-1:123456789012:database/mydb"
                );
                """;
        List<String> errors = provider.validate(policy);
        assertFalse(errors.isEmpty(), "INSERT on Database should fail validation (INSERT only applies to Table)");
    }
}
