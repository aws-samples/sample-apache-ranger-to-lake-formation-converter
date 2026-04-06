# Cedar Schema Design Guide

## Schema Format Overview

Cedar schemas define the entity types, actions, and their relationships that your authorization model supports. Schemas enable compile-time validation of policies, catching errors before runtime.

Cedar supports two schema formats (interchangeable, convertible via CLI):
- **Human-readable format** (`.cedarschema`) — Cedar-like syntax, recommended for authoring
- **JSON format** (`.cedarschema.json`) — Programmatic construction and parsing

## Namespace Declaration

Namespaces group related entity types and actions to avoid ambiguity.

```cedarschema
namespace DataCatalog {
    entity Database;
    entity Table in [Database];
    entity Column in [Table];

    action "SELECT" appliesTo {
        principal: [Principal],
        resource: [Table, Column]
    };
}
```

Rules:
- Entities declared in a namespace must be fully qualified when referenced outside: `DataCatalog::Database`
- Entities without a namespace are referenced without qualification
- Multiple namespace declarations with the same name are disallowed
- The `__cedar` namespace is reserved for primitive and extension types
- You can annotate namespaces: `@doc("Data catalog entities") namespace DataCatalog { ... }`

## Entity Type Declarations

### Basic Entity Types

```cedarschema
// Simple entity with no attributes
entity UserGroup;

// Entity with attributes
entity User in [UserGroup] {
    department: String,
    jobLevel: Long,
    email: String,
};

// Entity with optional attributes (? suffix)
entity Account {
    owner: User,
    admins?: Set<User>,  // optional
};
```

### Membership Relations (Hierarchy)

The `in` keyword defines parent-child relationships (entity hierarchy).

```cedarschema
// Single parent type
entity User in [UserGroup];

// Multiple parent types (brackets required for multiple)
entity Photo in [Album, SharedFolder];

// Self-referential hierarchy (folders containing folders)
entity Folder in [Folder];

// No parent (top-level entity)
entity Database;
```

This enables the `in` operator in policies:
```cedar
// Works because Photo is declared `in [Album]`
permit(principal, action, resource is Photo in Album::"vacation");
```

### Attribute Types

| Type | Example | Description |
|------|---------|-------------|
| `String` | `name: String` | Text values |
| `Long` | `age: Long` | 64-bit integers |
| `Bool` | `active: Bool` | Boolean values |
| `Set<T>` | `tags: Set<String>` | Set of values |
| `Entity` | `owner: User` | Reference to another entity |
| `Record` | `{ key: Type }` | Nested record |
| `ipaddr` | `ip: ipaddr` | IP address extension type |
| `decimal` | `score: decimal` | Decimal extension type |

### Nested Records

```cedarschema
entity Document {
    owner: User,
    metadata: {
        createdAt: Long,
        tags: Set<String>,
        location: {
            latitude: decimal,
            longitude: decimal,
        },
    },
};
```

Access in policies: `resource.metadata.location.latitude`

### Tags (Dynamic Key-Value)

```cedarschema
// Entities with string-typed tags (any number of arbitrary-named tags)
entity User in [Group] {
    personalGroup: Group,
} tags String;
```

Access in policies: `principal.getTag("department")`

### Enumerated Entities

```cedarschema
// Only these specific EIDs are valid
@doc("Only three valid groups")
entity Group enum ["G1", "G2", "G3"];
```

### Multiple Entity Types with Same Shape

```cedarschema
// Declares UserA, UserB, UserC with identical definitions
entity UserA, UserB, UserC in [Group] {
    name: String,
};
```

## Action Declarations

Actions define what operations are possible and what entity types they apply to.

```cedarschema
action "viewPhoto" appliesTo {
    principal: User,           // single type (no brackets needed)
    resource: Photo,
    context: {                 // optional context shape
        authenticated: Bool,
    }
};

// Multiple principal/resource types
action "describe" appliesTo {
    principal: [User, ServiceAccount],
    resource: [Database, Table],
};

// Action groups (hierarchy)
action "readOnly" in [Action::"admin"];
action "view" in [Action::"readOnly"] appliesTo {
    principal: User,
    resource: Document,
};
```

Rules:
- `appliesTo` requires `principal` and `resource` keys
- `context` is optional; defaults to empty record if omitted
- Without `appliesTo`, the action doesn't apply to any principal/resource
- Action names can be identifiers or strings
- Actions can be members of action groups (hierarchy)

## Common Types

Reusable type aliases to avoid duplication.

```cedarschema
namespace MyApp {
    // Define common types
    type AuthContext = {
        authenticated: Bool,
        mfaUsed: Bool,
        srcIp: ipaddr,
    };

    type ResourceMetadata = {
        owner: User,
        createdAt: Long,
        tags: Set<String>,
    };

    // Use in entity declarations
    entity Document {
        metadata: ResourceMetadata,
    };

    // Use in action context
    action "view" appliesTo {
        principal: User,
        resource: Document,
        context: AuthContext,
    };
}
```

Rules:
- Syntax: `type <Name> = <Type>;`
- Circular references are disallowed
- Name resolution priority: common type > entity type > primitive/extension type
- Use `__cedar::Long` to explicitly reference primitive types if shadowed

## Type Name Resolution

When type names conflict, Cedar resolves in this priority order:

```
common type > entity type > primitive/extension type
```

Use `__cedar::` prefix to explicitly reference primitives/extensions:
```cedarschema
namespace Demo {
    // This shadows the ipaddr extension type
    type ipaddr = {
        repr: String,
        isV4: Bool,
    };

    entity Host {
        ip: ipaddr,              // resolves to common type above
        realIp: __cedar::ipaddr, // explicitly the extension type
    };
}
```

## Schema Design Patterns

### Data Catalog Pattern (Database/Table/Column)

```cedarschema
namespace DataCatalog {
    entity Principal;
    entity Database;
    entity Table in [Database];
    entity Column in [Table];
    entity DataLocation;

    action "SELECT" appliesTo {
        principal: [Principal],
        resource: [Table, Column]
    };
    action "INSERT" appliesTo {
        principal: [Principal],
        resource: [Table]
    };
    action "DESCRIBE" appliesTo {
        principal: [Principal],
        resource: [Database, Table]
    };
    action "CREATE_TABLE" appliesTo {
        principal: [Principal],
        resource: [Database]
    };
    action "DATA_LOCATION_ACCESS" appliesTo {
        principal: [Principal],
        resource: [DataLocation]
    };
}
```

### Multi-Tenant SaaS Pattern

```cedarschema
namespace SaaS {
    entity Tenant;
    entity User in [UserGroup, Tenant] {
        email: String,
        role: String,
    };
    entity UserGroup in [Tenant];

    entity Resource in [Tenant] {
        owner: User,
        visibility: String,
    };

    action "read" appliesTo {
        principal: [User],
        resource: [Resource],
        context: { tenantId: String }
    };
    action "write" appliesTo {
        principal: [User],
        resource: [Resource],
        context: { tenantId: String }
    };
}
```

### Document Management Pattern

```cedarschema
namespace DocCloud {
    entity User in [UserGroup] {
        department: String,
    };
    entity UserGroup;

    entity Document in [Folder] {
        owner: User,
        isPublic: Bool,
        classification: String,
    };
    entity Folder in [Folder];

    action "View" appliesTo {
        principal: [User],
        resource: [Document, Folder],
        context: { mfa_authed: Bool, src_ip: ipaddr }
    };
    action "Delete" appliesTo {
        principal: [User],
        resource: [Document],
        context: { mfa_authed: Bool, src_ip: ipaddr }
    };
}
```

## Schema Validation

When you validate policies against a schema, Cedar checks:
- Entity types referenced in policies exist in the schema
- Actions referenced in policies exist and are applicable to the specified principal/resource types
- Attribute access matches the schema (correct names and types)
- Operator usage matches operand types (e.g., `>` only on `Long`)
- Set operations use compatible types

Validation is optional but strongly recommended. It catches errors at policy authoring time rather than at runtime.

## Schema Design Best Practices

- Always use namespaces to avoid entity type collisions
- Model real-world containment as entity hierarchy (`in`)
- Use `appliesTo` on every action to constrain valid principal/resource combinations
- Define context shape for actions that need session data
- Use common types to avoid duplicating record shapes
- Make attributes optional (`?`) only when they genuinely may be absent
- Keep schemas focused — one namespace per domain
- Use annotations (`@doc`) to document entity types and actions
- Validate all policies against the schema before deployment
- Design for extensibility — new actions can be added without modifying existing policies

Content was rephrased for compliance with licensing restrictions. Source: [docs.cedarpolicy.com](https://docs.cedarpolicy.com/schema/human-readable-schema.html)
