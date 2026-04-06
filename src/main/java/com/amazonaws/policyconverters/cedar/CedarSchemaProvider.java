package com.amazonaws.policyconverters.cedar;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.exception.InternalException;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads and provides the DataCatalog Cedar schema for validation
 * and partial evaluation.
 */
public class CedarSchemaProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CedarSchemaProvider.class);
    private static final String SCHEMA_RESOURCE_PATH = "cedar/datacatalog.cedarschema";

    private final Schema schema;
    private final AuthorizationEngine engine;

    public CedarSchemaProvider() {
        this.schema = loadSchema();
        this.engine = new BasicAuthorizationEngine();
    }

    /**
     * Load the Cedar schema from the classpath resource.
     */
    public Schema loadSchema() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Cedar schema resource not found on classpath: " + SCHEMA_RESOURCE_PATH);
            }
            String schemaText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new Schema(schemaText);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Cedar schema resource: " + SCHEMA_RESOURCE_PATH, e);
        }
    }

    /**
     * Get the loaded schema instance.
     */
    public Schema getSchema() {
        return schema;
    }

    /**
     * Validate Cedar policy text against the loaded schema.
     *
     * @param cedarPolicyText the Cedar policy text to validate
     * @return list of validation error messages; empty if valid
     */
    public List<String> validate(String cedarPolicyText) {
        try {
            PolicySet policySet = PolicySet.parsePolicies(cedarPolicyText);
            return validatePolicySet(policySet);
        } catch (InternalException e) {
            LOG.warn("Failed to parse Cedar policy text: {}", e.getMessage());
            return List.of("Policy parse error: " + e.getMessage());
        }
    }

    /**
     * Validate a PolicySet against the loaded schema.
     *
     * @param policySet the PolicySet to validate
     * @return list of validation error messages; empty if valid
     */
    public List<String> validatePolicySet(PolicySet policySet) {
        try {
            ValidationRequest request = new ValidationRequest(schema, policySet);
            ValidationResponse response = engine.validate(request);
            if (response.validationPassed()) {
                return Collections.emptyList();
            }
            List<String> errors = new ArrayList<>();
            response.errors.ifPresent(errorList ->
                    errorList.forEach(error -> errors.add(error.message)));
            if (errors.isEmpty()) {
                errors.add("Schema validation failed");
            }
            return errors;
        } catch (AuthException e) {
            LOG.warn("Schema validation threw exception: {}", e.getMessage());
            return List.of("Validation error: " + e.getMessage());
        }
    }
}
