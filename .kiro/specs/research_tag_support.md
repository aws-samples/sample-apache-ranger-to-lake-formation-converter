# Tag Syncing Research: Ranger ↔ Lake Formation via Cedar

## Current State

Tag-based policies from Ranger are detected and skipped during conversion. `PolicyConverter.isTagBasedPolicy()` records a `TAG_BASED_POLICY` gap entry and produces zero Cedar statements. On the LF side, `LFPermissionFetcher.reverseMapResource()` also skips `LFTagPolicyResource` entries. Tags are a known hole in the pipeline.

## The Fundamental Mismatch

Ranger tags (especially via Apache Atlas) and LF-Tags are structurally different systems:

- **Ranger/Atlas tags**: Classification-based metadata attached to resources. Tag-based policies say "if resource has tag X, allow action Y."
- **LF-Tags**: Key-value pairs attached to Glue resources. LF tag-based access control (TBAC) grants permissions on resources matching tag expressions like `(environment=prod AND department=finance)`.

## Simplifying Assumptions

1. The LakeFormation service definition does not support Deny policies, so we do not need to worry about deny overlap at the LF grant level.
2. Ranger works on granting access at a single-tag level, not tag-expression level. This makes the Ranger → LF mapping relatively simple.
3. Atlas tags can be mapped to boolean LF-Tags. For example, Atlas tag `PII` → LF-Tag `PII=true`. The LF TBAC expression is then a simple existence check.

## Cedar Schema Extension for Tags

The current Cedar schema would need a `tags` attribute on resource entities:

```cedar
namespace DataCatalog {
    entity Database in [Catalog] {
        tags: Set<{key: String, value: String}>,
    };

    entity Table in [Database] {
        tags: Set<{key: String, value: String}>,
    };
}
```

## Cedar Policy Examples

Single-tag grant (the common case):

```cedar
@source("ranger-tag-policy-42")
permit(
    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/analyst",
    action == DataCatalog::Action::"SELECT",
    resource
)
when { resource.tags.contains({key: "PII", value: "true"}) };
```

Compound tag grant (if needed in the future):

```cedar
@source("ranger-tag-policy-55")
permit(
    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/finance-team",
    action == DataCatalog::Action::"SELECT",
    resource
)
when {
    resource.tags.contains({key: "environment", value: "prod"}) &&
    resource.tags.contains({key: "department", value: "finance"})
};
```

## Second Cedar Policy Store

A separate Cedar policy store for tags is recommended. Reasons:

1. **Structural difference**: Current policies use fully-resolved resource references (`resource == DataCatalog::Table::"arn:aws:glue:..."`). Tag policies use unbound `resource` with `when` conditions. These map to different LF API calls (named-resource grants vs. `LFTagPolicy` grants).
2. **Converter separation**: `CedarToLFConverter` does regex-based parsing expecting `resource == SomeType::"some-arn"`. Tag policies need a different extraction path for `when` clause predicates.
3. **Diff isolation**: `SyncService.computeDiff()` compares `LFPermissionOperation` objects. Tag-based operations need a different `LFResource` shape (tag expressions vs. catalog/database/table names). Separate stores avoid cross-contamination.
4. **Independent sync cadence**: Tag definitions change at a different rate than resource-level policies. A tag rename in Atlas shouldn't trigger a full re-diff of named-resource grants.

## Translation Pipeline

```
Atlas/Ranger Tags                Tag Sync Pipeline                    AWS
┌──────────────┐                ┌─────────────────────┐
│ Atlas tags   ├───────────────►│ TagResolver         │
│ on resources │                │   │                 │
└──────────────┘                │   ▼                 │
                                │ LF AddLFTagsToResource ──────► LF Tag API
┌──────────────┐                │                     │
│ Ranger tag   ├───────────────►│ TagPolicyToCedar    │
│ policies     │                │   │                 │
└──────────────┘                │   ▼                 │
                                │ Cedar Tag PolicySet │
                                │   │                 │
                                │   ▼                 │
                                │ CedarToLFTagGrant   ──────► LF TBAC API
                                └─────────────────────┘
```

Two sub-problems:

1. **Tag metadata sync**: Pull tag definitions from Atlas, create corresponding LF-Tags via `CreateLFTag`, attach to Glue resources via `AddLFTagsToResource`. This is a data sync, not a policy sync.
2. **Tag policy sync**: Convert Ranger tag-based policies → Cedar tag policies → LF TBAC grants via `GrantPermissions` with `LFTagPolicy` resource type.

## The Deny/Exception Problem

Even though the LF service definition doesn't support deny, Ranger itself does. Three scenarios to consider:

### Scenario 1: Tag allow + tag deny on the same tag

Example: permit SELECT on PII-tagged resources, forbid SELECT on PII-tagged resources for a specific user.

Stays entirely within the tag policy store. Cedar resolves it naturally — forbid wins unless there's a deny-exception. Since LF TBAC is grant-only, resolve before hitting LF: if forbid wins, don't emit the TBAC grant. Same pattern `CedarToLFConverter` already uses for named resources.

### Scenario 2: Tag allow + named-resource deny (cross-domain)

Example: "Analysts can SELECT anything tagged PII" (tag policy) but "analysts cannot SELECT `finance_db.salary_table`" (named-resource deny).

These live in two separate Cedar policy stores. Neither store alone has enough information to resolve the conflict.

**Option A — Materialize tag grants into named-resource grants.** Expand the tag-based grant into concrete resources (query which resources have the PII tag), check each against named-resource forbids, emit individual named-resource grants minus the denied ones. Defeats the purpose of TBAC.

**Option B — Accept the semantic gap (recommended).** Tag-based grants go through LF TBAC. Named-resource denies go through named-resource revokes. LF doesn't have cross-cutting deny semantics between TBAC and named grants — they're additive. Record this as a gap: "tag-based grant on PII conflicts with named-resource deny on salary_table — LF cannot enforce this deny."

**Option C — Resolve in Cedar, emit only named-resource grants.** Use Cedar as the true policy engine, materialize effective permissions per principal per resource, emit only named-resource LF grants. Correct semantics but loses TBAC scalability entirely.

### Scenario 3: Deny-exceptions crossing the boundary

Same logic as Scenario 2. If a deny-exception references a tag-based forbid, cross-store resolution is needed. Recommendation: record the gap, move on.

### Recommendation: Option B

- The pipeline already accepts semantic gaps (`GapReporter` infrastructure). A "cross-domain deny conflict" gap type fits naturally.
- In practice, orgs using tag-based policies in Ranger are unlikely to also have conflicting named-resource denies on the same resources.
- Options A and C require expanding tags to concrete resources on every sync cycle — the `CatalogResolver` wildcard expansion problem but worse, since tag membership changes independently.
- Preserves TBAC scalability: one LF tag grant covers hundreds of tables without enumerating them.

## Implementation Path

1. **Tag metadata sync** runs first: Atlas tags → LF-Tags (create/update/attach to resources)
2. **Tag policy sync**: Ranger tag policies → Cedar tag policy store → LF TBAC grants
3. **Named-resource policy sync**: existing pipeline, unchanged
4. **Cross-domain conflict detector**: after both syncs complete, scan for principals appearing in both stores with conflicting permit/forbid semantics. Emit gap entries for conflicts. No attempt to resolve — just visibility.

Step 4 is lightweight. The Cedar policy text from both stores and principal extraction via `CedarPolicySet.getPrincipals()` already exist. Cross-reference principals and check if any forbid in one store targets a resource covered by a permit in the other.

## Complexity Estimate

- **Tag metadata sync** alone is comparable in complexity to the entire current named-resource pipeline.
- **Tag policy conversion** adds another layer on top.
- **Tag vocabulary mapping** (Atlas classifications → LF-Tag key-value pairs) is the hardest design problem due to the semantic mismatch. Atlas supports hierarchical tag classifications that LF-Tags don't.
- **Drift detection**: Tags on resources can change outside the pipeline (e.g., someone adds a tag in the LF console). The tag metadata sync needs to handle drift, which the current permission-only pipeline doesn't worry about.
- Rough estimate: ~2-3x the current codebase complexity.

## Open Questions

- [ ] Should we support compound tag expressions from Ranger, or single-tag only?
- [ ] How do we handle Atlas tag hierarchy flattening into LF-Tag key-value pairs?
- [ ] What is the sync cadence for tag metadata vs. tag policies?
- [ ] Do we need a separate checkpoint store for the tag pipeline?
- [ ] How do we handle tag renames in Atlas (delete old LF-Tag + create new, or update)?
- [ ] Should the cross-domain conflict detector block the sync or just report?
