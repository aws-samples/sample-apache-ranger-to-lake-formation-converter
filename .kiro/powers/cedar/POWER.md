---
name: "cedar"
displayName: "Cedar Policy Language"
description: "Comprehensive guide for Amazon's Cedar policy language covering policy syntax, schema design, entity modeling, partial evaluation, and Java SDK integration."
keywords: ["cedar", "authorization", "policy", "cedarpolicy", "verified-permissions"]
author: "Kiro Power Builder"
---

# Cedar Policy Language

## Overview

Cedar is an open-source authorization policy language created by Amazon. It lets you decouple authorization logic from application code by expressing access control rules as human-readable policy statements. Cedar supports both role-based access control (RBAC) and attribute-based access control (ABAC).

Cedar policies answer the question: "Can this principal perform this action on this resource?" The Cedar authorization engine evaluates requests against a policy set and returns Allow or Deny. An explicit `forbid` always overrides any `permit` (deny wins).

This power covers Cedar policy syntax, schema design, entity modeling, partial evaluation, the cedar-java SDK, and best practices for building Cedar-based authorization systems.

## Available Steering Files

- **schema-design** — Deep dive into Cedar schema syntax, entity type declarations, action definitions, namespaces, common types, and schema validation patterns
- **java-sdk** — Guide to using the cedar-java SDK (v4.2.3+) with JDK 17+, including Maven setup, policy parsing, schema validation, partial evaluation via JNI/Rust FFI, and common Java integration patterns

## Core Concepts

### Terminology

- **Principal** — The entity making a request (user, role, service)
- **Action** — The operation being performed (view, edit, delete)
- **Resource** — The entity being acted upon (document, photo, database)
- **Context** — Transient/session data for the request (IP address, MFA status, time)
- **Policy** — A `permit` or `forbid` statement defining access rules
- **Policy Set** — A collection of policies evaluated together
- **Schema** — Defines entity types, actions, and their relationships
- **Entity** — An instance of a type, identified by `Type::"id"` (e.g., `User::"alice"`)
- **Namespace** — A prefix for entity types to avoid ambiguity (e.g., `DataCatalog::Database`)

### Authorization Decision Logic

1. All policies in the policy set are evaluated against the request
2. If at least one `permit` matches AND zero `forbid` policies match → **Allow**
3. If any `forbid` matches OR zero `permit` policies match → **Deny**
4. Default (empty policy set) → **Deny** (implicit deny)
5. An explicit `forbid` always overrides any `permit`

## Policy Syntax

### Basic Structure

```cedar
// Annotations (optional, no effect on evaluation)
@id("policy-001")
@description("Allow analysts to view reports")

// Effect: permit or forbid
permit(
    // Scope (required): principal, action, resource
    principal == User::"alice",
    action == Action::"view",
    resource == Photo::"vacation.jpg"
)
// Conditions (optional)
when {
    resource.isPublic
}
unless {
    principal == resource.owner
};
```

### Scope Patterns

```cedar
// Match any principal / action / resource
principal
action
resource

// Match a specific entity
principal == User::"alice"
action == Action::"view"
resource == Photo::"vacation.jpg"

// Match by group membership (hierarchy)
principal in Group::"analysts"
resource in Album::"vacation"

// Match by entity type
principal is User
resource is Photo

// Match by type AND group
principal is User in Group::"analysts"
resource is Photo in Album::"vacation"

// Match multiple actions
action in [Action::"view", Action::"list", Action::"describe"]

// Match action group
action in Action::"readOnly"
```

### Conditions

```cedar
// when — must evaluate to true for policy to match
when {
    principal.department == "Engineering" &&
    principal.jobLevel >= 5
}

// unless — must evaluate to false for policy to match
unless {
    context.authentication.usedMFA
}

// Attribute access
when { resource.owner == principal }
when { context.time.now < 1698423180 }
when { principal in resource.sharedWith }
```

### Operators

| Category | Operators |
|----------|-----------|
| Comparison | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| Logical | `&&`, `||`, `!` |
| Arithmetic | `+`, `-`, `*` |
| Hierarchy | `in` |
| Type test | `is` |
| Set | `.contains()`, `.containsAll()`, `.containsAny()` |
| Record | `has` (attribute existence check) |
| String | `.like()` (wildcard matching) |
| IP | `ip()`, `.isIpv4()`, `.isIpv6()`, `.isLoopback()`, `.isMulticast()`, `.isInRange()` |
| Decimal | `decimal()` |

### Annotations

```cedar
// Annotations go before the effect, key-value pairs
@id("policy-42")
@advice("Contact admin if denied")
@source("ranger-policy-42")
permit(
    principal == User::"alice",
    action == Action::"view",
    resource
);
```

### Policy Templates

```cedar
// Template with placeholders (?principal, ?resource)
permit(
    principal == ?principal,
    action == Action::"view",
    resource in ?resource
);

// Instantiate by binding placeholders to specific values
// (done programmatically via the SDK)
```

## Common Policy Patterns

### RBAC — Role-Based Access Control

```cedar
// Members of admin group can do anything
permit(
    principal in Group::"admins",
    action,
    resource
);

// Analysts can read reports
permit(
    principal in Group::"analysts",
    action in [Action::"view", Action::"list"],
    resource is Report
);
```

### ABAC — Attribute-Based Access Control

```cedar
// Owners can do anything with their resources
permit(
    principal,
    action,
    resource
) when {
    resource.owner == principal
};

// Only allow access from company network
permit(
    principal,
    action,
    resource
) when {
    context.srcIp.isInRange(ip("10.0.0.0/8"))
};

// Require MFA for sensitive operations
forbid(
    principal,
    action in [Action::"delete", Action::"modify"],
    resource
) unless {
    context.mfaAuthenticated
};
```

### Deny with Exceptions

```cedar
// Deny access to private resources
forbid(
    principal,
    action,
    resource
) when {
    resource.private
}
// ...unless you're the owner
unless {
    principal == resource.owner
};
```

### Hierarchical Resources

```cedar
// Access to a parent grants access to children
// (if entity hierarchy is modeled: Photo in Album)
permit(
    principal in Group::"friends",
    action == Action::"view",
    resource in Album::"vacation"
);
// This permits viewing any Photo that is in Album::"vacation"
```

## Schema Overview

Cedar schemas define entity types, actions, and their relationships. Schemas enable policy validation — catching errors before runtime.

```cedarschema
namespace PhotoFlash {
    // Entity types
    entity User in [UserGroup] {
        department: String,
        jobLevel: Long,
    };
    entity UserGroup;

    entity Album in [Album] {
        account: Account,
        private: Bool,
    };
    entity Account {
        owner: User,
        admins?: Set<User>,  // optional attribute
    };
    entity Photo in [Album] {
        account: Account,
        private: Bool,
    };

    // Actions with applicability
    action "viewPhoto" appliesTo {
        principal: User,
        resource: Photo,
        context: { authenticated: Bool }
    };
    action "uploadPhoto" appliesTo {
        principal: User,
        resource: Album,
        context: {
            authenticated: Bool,
            photo: { file_size: Long, file_type: String }
        }
    };
    action "listAlbums" appliesTo {
        principal: User,
        resource: Account,
        context: { authenticated: Bool }
    };
}
```

For detailed schema design guidance, read the **schema-design** steering file.

## Partial Evaluation

Partial evaluation lets you answer questions like "What resources can Alice access?" by evaluating policies with some unknowns. Instead of always returning Allow/Deny, it can return residual policies — simplified policies that represent the remaining constraints.

Key use cases:
- Enumerate effective permissions for a principal
- Pre-compute authorization decisions when some context is unavailable
- Convert residual policies to database queries for efficient data filtering

For Java SDK usage including partial evaluation, read the **java-sdk** steering file.

## Entity Identifier Best Practices

- Use unique, non-reusable identifiers (UUIDs, ARNs) — never human names
- For AWS resources, use ARN format: `arn:aws:service:region:account:resource-type/name`
- Add comments for readability: `User::"a1b2c3d4-e5f6..." // alice`
- Use namespaces to avoid ambiguity across services

```cedar
// Good: unique identifiers
principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole"
resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/db/orders"

// Bad: reusable human names
principal == User::"alice"
```

## Troubleshooting

### Policy doesn't match when expected

- Check that scope constraints match exactly (entity type + ID)
- Verify `when` conditions evaluate to `true` and `unless` conditions to `false`
- Remember: default is Deny — you need at least one matching `permit`
- Check entity hierarchy: `principal in Group::"X"` requires the principal entity to actually be a member of that group in the entity data

### Forbid overriding permits unexpectedly

- Any matching `forbid` overrides all `permit` policies — this is by design
- Use `unless` clauses on `forbid` policies to create exceptions
- Check for overly broad `forbid` policies (e.g., `forbid(principal, action, resource)`)

### Schema validation errors

- Entity type not found: check namespace qualification (`MyNS::User` vs `User`)
- Action not applicable: verify `appliesTo` in schema matches policy scope
- Attribute type mismatch: e.g., comparing `Long` with `String` (`principal.age > "21"` fails)
- Missing attribute: use `has` operator before accessing optional attributes

### Common syntax mistakes

- Missing semicolon at end of policy
- Using `=` instead of `==` for comparison
- Forgetting quotes around entity IDs: `User::alice` should be `User::"alice"`
- Using `or`/`and` instead of `||`/`&&`

## Best Practices

- Always define a schema and validate policies against it
- Use namespaces to organize entity types by domain
- Prefer specific scope constraints over broad `when` conditions
- Use `forbid` sparingly — broad forbids are hard to debug
- Keep policies small and focused (one concern per policy)
- Use annotations for traceability (`@id`, `@source`)
- Use policy templates for repeated patterns across principals/resources
- Test policies with the Cedar CLI or SDK before deploying
- Use unique, non-reusable entity identifiers (UUIDs or ARNs)
- Model entity hierarchies to leverage the `in` operator for group-based access

## References

- [Cedar Policy Language Reference](https://docs.cedarpolicy.com/) — Official documentation
- [Cedar GitHub](https://github.com/cedar-policy/cedar) — Source code and Rust SDK
- [cedar-java on Maven Central](https://central.sonatype.com/artifact/com.cedarpolicy/cedar-java) — Java SDK
- [Cedarland Blog](https://cedarland.blog/) — Community guides and tutorials
- [Cedar Playground](https://www.cedarpolicy.com/en/playground) — Interactive policy testing

Content was rephrased for compliance with licensing restrictions. Sources: [docs.cedarpolicy.com](https://docs.cedarpolicy.com/), [cedarland.blog](https://cedarland.blog/usage/partial-evaluation/content.html)
