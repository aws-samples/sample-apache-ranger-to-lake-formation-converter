# Cedar-Engine Oracle for `CedarToLFConverter`

**Status:** Proposed — REVISED after two independent expert reviews (see "Review outcome").
**Scope of this doc:** focused unit-test oracle. Property-based expansion is a follow-up.

## Problem

`CedarToLFConverter` decides which Lake Formation grants a Cedar policy set produces.
It does **not** use the real Cedar engine — it manually parses `policySet.toCedarString()`
with regexes and reimplements authorization resolution by hand (permit/forbid collection,
forbid-wins precedence, deny-exceptions, and ancestor forbid suppression via
`isAncestorResource`). Compounding the risk, the production converter and the simulator's
`ExpectedPermissionsComputer` are two *independent* hand-implementations checked only
against each other, with no ground-truth reference. This is how the recent `column=*` and
hive-service oracle bugs slipped in.

Original idea: use the real Cedar `BasicAuthorizationEngine.isAuthorized` as an independent
oracle validating the converter in both directions.

## Review outcome (why the scope is narrowed)

Two independent max-effort expert reviews **both** concluded the original design's central
mechanism is architecturally wrong, and each was verified against the code:

- **The Cedar engine cannot validate ancestor-forbid suppression.** The converter emits
  `resource == DataCatalog::Table::"<arn>"` (exact match) — never `resource in [...]`
  ([RangerToCedarConverter.java:349](../../src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java#L349)).
  Cedar only consults entity **parent links** for the `in` operator. So a database-level
  `forbid(... resource == Database::"db")` does **not** fire for a request on
  `Table::"db/t"`, and the engine returns ALLOW — while the converter's `isAncestorResource`
  ([CedarToLFConverter.java:495](../../src/main/java/com/amazonaws/policyconverters/cedar/CedarToLFConverter.java#L495))
  correctly suppresses it. The proposed entity hierarchy is **inert** for `==` policies.
  The engine would fire false under-grants on every correctly-suppressed ancestor. This
  kills the scenarios (4,5,6) that motivated the oracle.

- **`@denyException` is a project annotation, not Cedar semantics.** Deny-exceptions are
  emitted as `permit(@denyException(...) ...)`
  ([RangerToCedarConverter.java:268](../../src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java#L268)).
  Cedar ignores annotations and applies forbid-wins, so it returns DENY where the converter
  correctly grants. The engine cannot be an oracle for deny-exceptions.

- **Row-filter permits break the engine.** They are emitted as
  `when { resource.rowFilter == "expr" }`, but `rowFilter` is not defined in
  `datacatalog.cedarschema`. The engine hits an undefined-attribute error → DENY → false
  over-grant failures, and a blind spot in the under-grant direction.

**Conclusion:** the engine is a valid oracle only for the subset of converter behavior
that is (a) expressed as standard Cedar and (b) evaluable by the engine — i.e. simple
permits, exact same-resource forbids, and cross-policy permission overlap. The highest-risk
logic (ancestor suppression, deny-exceptions, row filters) is project-specific semantics
layered on top of `==` policies or unschema'd attributes, and **stays covered by the
existing `CedarToLFConverterTest`** (which already has dedicated regression + `isAncestorResource`
unit tests).

This is a real reduction in the oracle's promised value. It is documented honestly rather
than forcing the engine into a role it cannot fill.

## Revised goal

Use `BasicAuthorizationEngine` as an independent oracle for the **engine-evaluable subset**,
in both directions, with all non-evaluable constructs explicitly **excluded** by a harness
guard (not silently). Primary value: the **over-grant direction** (existing tests do not
check "the engine forbids this grant") and **cross-policy overlap** (Bug #4 regression).

Non-goals: ancestor-forbid, deny-exception, and row-filter validation via the engine
(covered by existing unit tests); replacing the converter's regex parsing in production.

## Enumeration is bounded

- **Over-grant** — enumerate the converter's emitted grants (finite). For each, ask the
  engine; DENY ⇒ over-grant.
- **Under-grant** — enumerate the scenario's **explicitly declared** candidate triples
  (see "Permit enumeration" below); engine ALLOW but converter emitted no matching grant
  (same `sourcePolicyId`, same grantable) ⇒ under-grant.

## Permit enumeration: declare triples, do NOT parse (independence)

cedar-java 4.2.3 `Policy` exposes only raw text (`policySrc` / `getSource()`); there is no
typed AST accessor. Enumerating permits by regex would reuse the converter's own fragile
parsing approach and destroy the oracle's independence.

**Decision:** for the hand-authored scenarios, declare the expected candidate triples as
explicit test data next to the Cedar text:

```java
record Triple(String principalArn, String action, String resourceType, String resourceArn,
              boolean grantable, String sourcePolicyId) {}
record Scenario(String cedarText, List<Triple> candidateTriples) {}
```

The test author knows exactly what each scenario encodes — zero parsing, zero circular
dependency. (`Policy.toJson()` returns a structured AST and is the independent path for a
future jqwik property test; noted as follow-up, not used here.)

## Resource levels and per-level action validity

The converter maps to one of several LF resource types, each admitting different actions.
The reviews corrected the earlier claim that these are identical across adapters — they are
**not** (RangerServiceAdapter's DATABASE set includes `CREATE_DATABASE`; Hive's does not).
The oracle's expected-action logic must be per-adapter, or scenarios must fix one adapter.

| LF level             | Valid actions (Hive)                                   |
|----------------------|--------------------------------------------------------|
| `CATALOG`            | DESCRIBE, ALL, (CREATE_DATABASE per schema appliesTo)  |
| `DATABASE`           | CREATE_TABLE, ALTER, DROP, DESCRIBE, ALL               |
| `TABLE`              | SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, ALL     |
| `TABLE_WITH_COLUMNS` | SELECT, ALL                                            |
| `DATALOCATION`       | DATA_LOCATION_ACCESS                                   |

Notes carried from review: SELECT is invalid at DATABASE; a bare `database=db` maps to a
DATABASE grant (not `table=*`, no cascade); `database=db,table=*` expands via Glue to
concrete TABLE grants; `column=*` on a concrete table promotes to TABLE. **DATALOCATION and
CATALOG levels must be represented** or their grants become oracle blind spots / false
failures.

## Harness (test-only)

`src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterOracleTest.java`

1. **Exclusion guard (build FIRST, before scenarios).** Skip any candidate/grant whose
   permit is annotated `@denyException`, or whose permit carries a `when { ... }` row-filter
   clause. These are out of engine scope by construction; the harness must exclude them
   explicitly (not let them silently pass/fail).
2. **Entity store** — one `Entity` per principal / resource / action EUID referenced,
   including **DataLocation and Catalog** entities. Parent links MAY be added but are inert
   for `==` policies (kept minimal). No `Schema` and no request validation (so undefined
   attributes are not needed), but row-filter permits are excluded anyway per the guard.
3. **Engine call** — `new BasicAuthorizationEngine().isAuthorized(request, policySet, entities)`
   with `policySet = cedarPolicySet.getInternalPolicySet()`. Read the decision correctly:
   ```java
   AuthorizationResponse resp = engine.isAuthorized(req, ps, entities);
   if (resp.type != AuthorizationResponse.SuccessOrFailure.Success) fail("engine error: " + resp.errors);
   boolean allowed = resp.success.orElseThrow().isAllowed();  // Decision.Allow / Decision.Deny
   ```
   API corrections from review: `Decision.Allow`/`.Deny` (not `ALLOW`/`DENY`);
   `EntityTypeName.parse(...)` and `EntityUID.parse(...)` return `Optional` (use
   `.orElseThrow()`); the 4-arg `AuthorizationRequest(EntityUID, EntityUID, EntityUID,
   Map<String,Value>)` takes a plain context map (`Collections.emptyMap()`).
4. **Assertions** — over-grant: every emitted grant's (P, action, resource) is engine-ALLOW.
   Under-grant: every declared engine-ALLOW triple appears in the emitted grants, matched on
   `(principalArn, action, resource, grantable, sourcePolicyId)` — the **grantable flag and
   sourcePolicyId must be part of the match** (per review I-1/I-2), else a dropped grantable
   flag or a dropped per-policy grant is invisible.

## Focused scenarios (engine-evaluable subset only)

1. Simple permit → grant; engine ALLOW; both directions.
2. Permit + forbid on a **different exact resource** → no suppression; engine ALLOW.
3. Exact same-resource forbid → suppresses permit; engine DENY; converter emits nothing.
4. **Cross-policy overlap (Bug #4 regression)** — permit A `{SELECT,DESCRIBE}` and permit B
   `{SELECT,INSERT}` on the same (principal,resource) with **distinct `@source` values**;
   forbid removes B's INSERT only → SELECT/DESCRIBE survive, per-source matched.
5. `grantable` — permit with `@grantable("true")` → emitted op has grantable=true (asserts
   the flag, not just triple presence).
6. Level validity — database-level permit naming SELECT → no grant (SELECT invalid at
   DATABASE); engine agrees.
7. Wildcard expansion — `database=db, table=*` with in-test lister `db→[t1,t2]` → TABLE
   grants for both; engine ALLOW for each expanded table.
8. `column=*` promotion — `column=*` on concrete table with ALTER → TABLE-level ALTER grant.
9. DATA_LOCATION_ACCESS — datalocation permit → DATALOCATION grant; engine ALLOW (entity
   present in store).

Excluded (kept in `CedarToLFConverterTest`, NOT engine-validated, harness guard skips them):
ancestor database/table forbid suppression; deny-exception restoration; row-filter permits.

## What this proves / does not prove

**Proves:** for engine-evaluable policies, the converter's permit/forbid(exact)/level-validity/
cross-policy resolution matches the real Cedar engine — bidirectionally, with grantable and
source attribution checked.

**Does NOT prove:** ancestor-forbid suppression, deny-exceptions, row filters (engine cannot
evaluate these — covered by existing unit tests); that in-test wildcard expansion matches
live Glue (simulator's job).

## Risks / sequencing

1. Build the exclusion guard and a working `isAuthorized` smoke test (scenario 1) **first** —
   no existing test calls `isAuthorized` (only `parsePolicies`); the JNI authorize path is
   unproven in the test JVM and throws `AuthException` (distinct from parse's `InternalException`).
2. Then add scenarios. If scenario 1 fails, it is almost certainly an entity-store/EUID or
   JNI issue, not a converter bug.

## File / change summary

| File | Change |
|------|--------|
| `src/test/.../cedar/CedarToLFConverterOracleTest.java` | New. Exclusion guard, entity-store builder (incl. DataLocation/Catalog), in-test table lister, engine wrapper, 9 engine-evaluable scenarios. |

No production code changes. Ancestor/deny-exception/row-filter validation is explicitly out
of engine scope and remains in `CedarToLFConverterTest`.

## Follow-ups

- jqwik property test using `Policy.toJson()` for independent structured permit enumeration.
- If ancestor-forbid *engine* validation is ever wanted: migrate generated forbids to
  `resource in [parent]` form (a production change) — only then can the engine enforce it.
- Parallel oracle for the simulator's `ExpectedPermissionsComputer`.
