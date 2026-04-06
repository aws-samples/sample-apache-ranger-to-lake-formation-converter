# Cedar Java SDK Guide

## Overview

The `cedar-java` SDK provides Java bindings for the Cedar policy language via JNI (Rust FFI). It supports policy parsing, schema validation, authorization decisions, and partial evaluation. Requires JDK 17+.

## Maven Setup

```xml
<dependency>
    <groupId>com.cedarpolicy</groupId>
    <artifactId>cedar-java</artifactId>
    <version>4.2.3</version>
</dependency>
```

Ensure JDK 17+ compilation:
```xml
<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>
```

The cedar-java library includes native Rust binaries loaded via JNI. The native library must be loadable at runtime — if it fails, you'll get `UnsatisfiedLinkError` or `ExceptionInInitializerError`.

## Core Concepts

### Key Classes

| Class | Purpose |
|-------|---------|
| `AuthorizationEngine` | Main entry point for authorization decisions |
| `PolicySet` | Collection of Cedar policies |
| `Schema` | Cedar schema for validation |
| `Entity` | Represents a principal, resource, or other entity |
| `EntityUID` | Entity type + identifier pair |
| `Request` | Authorization request (principal, action, resource, context) |
| `AuthorizationResponse` | Result of an authorization decision |
| `PartialAuthorizationResponse` | Result of partial evaluation |

## Common Workflows

### 1. Parse and Validate Policies

```java
import com.cedarpolicy.*;
import com.cedarpolicy.model.*;
import com.cedarpolicy.model.schema.*;

// Load schema from string
String schemaText = Files.readString(Path.of("datacatalog.cedarschema"));
Schema schema = Schema.parse(schemaText);

// Parse policies from Cedar syntax
String policiesText = Files.readString(Path.of("policies.cedar"));
PolicySet policySet = PolicySet.parse(policiesText);

// Validate policies against schema
ValidationResult result = policySet.validate(schema);
if (!result.isValid()) {
    for (ValidationError error : result.getErrors()) {
        System.err.println("Validation error: " + error.getMessage());
    }
}
```

### 2. Make Authorization Decisions

```java
// Build entities
Set<Entity> entities = new HashSet<>();
// ... populate entities with principals, resources, groups

// Build request
EntityUID principal = EntityUID.parse("DataCatalog::Principal::\"arn:aws:iam::123456789012:role/Analyst\"");
EntityUID action = EntityUID.parse("DataCatalog::Action::\"SELECT\"");
EntityUID resource = EntityUID.parse("DataCatalog::Table::\"arn:aws:glue:us-east-1:123456789012:table/db/orders\"");

Map<String, Object> context = Map.of(
    "mfaAuthenticated", true,
    "sourceIp", "10.0.1.50"
);

Request request = new Request(principal, action, resource, context);

// Authorize
AuthorizationEngine engine = new AuthorizationEngine();
AuthorizationResponse response = engine.isAuthorized(request, policySet, entities, schema);

if (response.isAllowed()) {
    System.out.println("Access granted");
    System.out.println("Determining policies: " + response.getDeterminingPolicies());
} else {
    System.out.println("Access denied");
}
```

### 3. Partial Evaluation

Partial evaluation is used when some request components are unknown. It returns either a concrete decision or residual policies representing remaining constraints.

Use cases:
- Enumerate all resources a principal can access
- Pre-compute permissions when context is partially known
- Materialize effective grants by evaluating permit/forbid interactions

```java
// Build a partial request (resource is unknown)
Request partialRequest = Request.builder()
    .principal(principal)
    .action(action)
    // resource omitted — treated as unknown
    .context(context)
    .build();

// Run partial evaluation
PartialAuthorizationResponse partialResponse =
    engine.isAuthorizedPartial(partialRequest, policySet, entities, schema);

if (partialResponse.isConcrete()) {
    // Got a definitive answer even with unknowns
    AuthorizationResponse concrete = partialResponse.getConcreteResponse();
    System.out.println("Decision: " + (concrete.isAllowed() ? "Allow" : "Deny"));
} else {
    // Got residual policies — constraints that still need evaluation
    PolicySet residuals = partialResponse.getResiduals();
    for (Policy policy : residuals.getPolicies()) {
        System.out.println("Residual: " + policy.toCedarString());
    }
}
```

### 4. Building Entity Hierarchies

```java
// Create entity with parent relationships
EntityUID aliceUid = EntityUID.parse("User::\"alice\"");
EntityUID engineeringGroup = EntityUID.parse("UserGroup::\"engineering\"");

Entity alice = Entity.builder()
    .uid(aliceUid)
    .parent(engineeringGroup)  // alice is in engineering group
    .attribute("department", "Engineering")
    .attribute("jobLevel", 7)
    .build();

Entity engineering = Entity.builder()
    .uid(engineeringGroup)
    .build();

Set<Entity> entities = Set.of(alice, engineering);
```

### 5. Policy Construction (Programmatic)

```java
// Parse individual policy from string
String cedarText = """
    @id("policy-001")
    permit(
        principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/Analyst",
        action == DataCatalog::Action::"SELECT",
        resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/db/orders"
    );
    """;

PolicySet policySet = PolicySet.parse(cedarText);

// Combine multiple policy sets
PolicySet combined = PolicySet.merge(policySet1, policySet2);
```

### 6. Round-Trip Serialization

```java
// Format to Cedar syntax string
String cedarString = policySet.toCedarString();

// Parse back
PolicySet roundTripped = PolicySet.parse(cedarString);

// The two should be semantically equivalent
```

## Partial Evaluation for Effective Permissions

A common pattern is using partial evaluation to materialize effective permissions per principal. This correctly resolves permit/forbid interactions:

```java
// For each principal, evaluate with principal known but resource unknown
for (EntityUID principalUid : allPrincipals) {
    Request request = Request.builder()
        .principal(principalUid)
        .action(actionUid)
        // resource unknown
        .context(context)
        .build();

    PartialAuthorizationResponse response =
        engine.isAuthorizedPartial(request, policySet, entities, schema);

    if (response.isConcrete()) {
        if (response.getConcreteResponse().isAllowed()) {
            // Principal has access to ALL resources for this action
        } else {
            // Principal has access to NO resources for this action
        }
    } else {
        // Residuals describe which resources the principal can access
        // Convert residuals to queries or iterate over known resources
        PolicySet residuals = response.getResiduals();
        processResiduals(principalUid, residuals);
    }
}
```

## Error Handling

### JNI Initialization Failure

```java
try {
    AuthorizationEngine engine = new AuthorizationEngine();
} catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
    // Native library failed to load
    // Common causes: wrong JDK version, missing native binary for platform
    String msg = String.format(
        "Cedar native library failed to load. Platform: %s, JDK: %s. " +
        "Ensure JDK 17+ and compatible platform.",
        System.getProperty("os.name"),
        System.getProperty("java.version")
    );
    throw new RuntimeException(msg, e);
}
```

### Schema Validation Errors

```java
ValidationResult result = policySet.validate(schema);
if (!result.isValid()) {
    for (ValidationError error : result.getErrors()) {
        // Log and skip invalid policies
        logger.warn("Policy validation failed: {}", error.getMessage());
        // Record gap for monitoring
        gapReporter.recordGap(GapType.SCHEMA_VALIDATION_FAILURE, error.getMessage());
    }
}
```

### Partial Evaluation Errors

```java
try {
    PartialAuthorizationResponse response =
        engine.isAuthorizedPartial(request, policySet, entities, schema);
    // process response
} catch (Exception e) {
    // Log and skip this principal
    logger.error("Partial evaluation failed for principal {}: {}",
        principalUid, e.getMessage());
    // Continue with next principal
}
```

## Integration Patterns

### Pipeline Pattern (Source → Cedar → Target)

```
Source Policies → SourceAdapter → Cedar PolicySet
    → Schema Validation
    → Partial Evaluation (per principal)
    → Target Converter → Target Permissions
```

This pattern decouples source and target systems through Cedar as an intermediate representation. The source adapter maps source-specific concepts to Cedar, and the target converter maps Cedar results to target-specific operations.

### Adapter Pattern for Multiple Sources

```java
public interface SourcePolicyAdapter {
    String getServiceType();
    Set<String> mapAccessTypeToCedarActions(String sourceAccessType);
    CedarEntityRef buildEntityRef(Policy policy, String resourceLevel);
    String buildPrincipalRef(String resolvedPrincipalId);
}
```

Register adapters by service type and delegate mapping during conversion.

### Gap Reporting

When converting between systems, some features may not be representable in Cedar or the target system. Track these as "gaps":

```java
// During conversion
if (policyType == DATA_MASKING) {
    gapReporter.record(GapType.DATA_MASKING, policyId, "Data masking not supported in Cedar");
    return; // skip this policy
}

if (!targetSupportsAction(cedarAction)) {
    gapReporter.record(GapType.UNSUPPORTED_ACTION, policyId,
        "Action " + cedarAction + " not supported by target");
}
```

## Testing with cedar-java

### Property-Based Testing (jqwik)

```java
@Property(tries = 100)
void arnRoundTrip(
    @ForAll @From("validResourceComponents") GlueResourceComponents components
) {
    // Build ARN from components
    String arn = buildArn(components);
    // Parse back
    GlueResourceRef parsed = ArnParser.parse(arn);
    // Verify round-trip
    assertThat(parsed.getDatabaseName()).isEqualTo(components.databaseName());
    assertThat(parsed.getTableName()).isEqualTo(components.tableName());
}

@Property(tries = 100)
void permitForbidInteraction(
    @ForAll @From("validPrincipal") String principal,
    @ForAll @From("validAction") String action,
    @ForAll @From("validResource") String resource
) {
    // If both permit and forbid exist for same (P, A, R),
    // partial evaluation should produce Deny
    String policies = String.format("""
        permit(principal == %s, action == %s, resource == %s);
        forbid(principal == %s, action == %s, resource == %s);
        """, principal, action, resource, principal, action, resource);

    PolicySet ps = PolicySet.parse(policies);
    // ... evaluate and assert Deny
}
```

### Unit Testing

```java
@Test
void testSchemaValidation() {
    Schema schema = Schema.parse(schemaText);
    PolicySet policies = PolicySet.parse(policyText);

    ValidationResult result = policies.validate(schema);
    assertTrue(result.isValid(), "Policies should validate against schema");
}

@Test
void testBasicAuthorization() {
    // Setup
    PolicySet policies = PolicySet.parse("""
        permit(
            principal == User::"alice",
            action == Action::"view",
            resource == Doc::"readme"
        );
        """);

    Request request = new Request(
        EntityUID.parse("User::\"alice\""),
        EntityUID.parse("Action::\"view\""),
        EntityUID.parse("Doc::\"readme\""),
        Map.of()
    );

    // Act
    AuthorizationResponse response = engine.isAuthorized(request, policies, entities, schema);

    // Assert
    assertTrue(response.isAllowed());
}
```

## Troubleshooting

### UnsatisfiedLinkError at startup

- Ensure JDK 17+ is being used (not JDK 8 or 11)
- Verify the cedar-java JAR includes native binaries for your platform (linux-x86_64, macos-aarch64, etc.)
- Check that no other native library conflicts exist on the classpath

### Schema validation passes but authorization fails

- Validation checks policy structure, not entity data
- Ensure entity data provided to the authorization engine matches the schema
- Check that entity hierarchy (parent relationships) is correctly populated

### Partial evaluation returns unexpected residuals

- Residuals contain `true && ...` expressions — this is expected behavior (type safety preservation)
- Ensure all known values are provided in the request context
- Unknown values propagate through expressions, preventing simplification

### Performance considerations

- Cedar evaluation is designed for low-latency, real-time decisions
- Policy sets are indexed for quick retrieval
- For large policy sets, consider partitioning by principal or resource type
- Partial evaluation is more expensive than concrete evaluation — use judiciously

Content was rephrased for compliance with licensing restrictions. Sources: [docs.cedarpolicy.com](https://docs.cedarpolicy.com/), [cedarland.blog](https://cedarland.blog/usage/partial-evaluation/content.html), [central.sonatype.com](https://central.sonatype.com/artifact/com.cedarpolicy/cedar-java)
