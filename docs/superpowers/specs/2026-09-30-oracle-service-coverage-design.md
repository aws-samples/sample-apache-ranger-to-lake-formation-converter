# Oracle Service Coverage: Align `ExpectedPermissionsComputer` with the Production Converter

**Date:** 2026-09-30
**Status:** Approved
**Scope:** `simulator` Maven project only. No production converter changes.

## Why

`ExpectedPermissionsComputer` is a deliberate clean-room reimplementation of the Ranger→Lake
Formation mapping, used by the simulator to validate that the production converter's output matches
an independently-derived expectation. Its header forbids importing anything from
`com.amazonaws.policyconverters`, which is precisely what makes it a valid oracle: if it could reuse
the converter's mapping tables it would agree by construction and validate nothing.

That independence has a maintenance cost — when the production adapters gain a capability, the
oracle does not, and the two silently diverge. Several such divergences have accumulated. Each one
is a place where the oracle will report a disagreement that is the oracle's fault, not the
converter's, which is worse than no validation: it trains the reader to ignore the output.

This spec closes the divergences that exist today. It does **not** wire the oracle into the
assessment tool — that requires extracting a shared `oracle/` Maven project and is tracked
separately in project memory.

## Current divergences

Established by reading the current source, not assumed:

| # | Divergence | Current oracle behaviour | Converter behaviour | Consequence |
|---|---|---|---|---|
| 1 | `all` on `lakeformation` | `ExpectedPermissionsComputer.java:562` expands to `{SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE}` | `RangerServiceAdapter` emits `ALL` | Disagreement on every LF-service `all` policy, and inconsistent with `hiveMap` in the same file |
| 2 | `all` on `trino` | **absent** from `trinoMap` (starts `:581`) | `TrinoServiceAdapter` emits `ALL` | Oracle expects zero permissions where the converter grants `ALL` — a false under-grant |
| 3 | `update`, `read`, `write` on `trino` | absent from `trinoMap` | mapped to `INSERT`, `SELECT`, `INSERT` | False under-grants |
| 4 | `presto` service type | **not registered** at `:604-607` | `PrestoServiceAdapter` fully supported | Every Presto policy yields zero expected permissions |
| 5 | `url` resource key | only `datalocation` is read (`:351-353`) | `url` → `DATA_LOCATION_ACCESS` for hive, emr-spark, trino, presto | False under-grants on every `url` policy |

Note that `hiveMap.put("all", Set.of("ALL"))` is **already correct** — the expansion problem is
confined to `lfMap`.

## Design

### 1. `lfMap`: pass `all` through

Replace the six-permission expansion at `:562` with `Set.of("ALL")`.

This follows the decision recorded in `DESIGN.md` under *Why `all` maps to LF `ALL`*. The short
version: Ranger's `impliedGrants` for `all` include privileges Lake Formation cannot express at all
(Hive's `index`, `lock`, `repladmin`, `serviceadmin`, `refresh`; Presto/Trino's `grant`, `revoke`,
`impersonate`, `execute`), so a faithful expansion is impossible — it would silently narrow `all` to
the mappable subset. Passing through to LF's open-ended `ALL` is the closer translation of
"everything".

### 2. `trinoMap`: add the missing access types

Add, matching `TrinoServiceAdapter`:

```java
trinoMap.put("all",    Set.of("ALL"));
trinoMap.put("update", Set.of("INSERT"));
trinoMap.put("read",   Set.of("SELECT"));
trinoMap.put("write",  Set.of("INSERT"));
```

**Deliberately still absent:** `grant`, `revoke`, `execute`, `impersonate`. These are declared
known-unmappable by the production adapters. An access type missing from the map falls through
`accessMap.get(type)` at `:325` as `null` and contributes no permission, which is exactly the
required behaviour. Adding them mapped to anything would be wrong. This omission is
load-bearing and must be covered by a test so a future reader does not "complete" the map.

### 3. Register `presto` against the same map

```java
result.put("presto", Collections.unmodifiableMap(trinoMap));
```

`PrestoServiceAdapter` and `TrinoServiceAdapter` are line-for-line identical in their action
mappings, so sharing one map is correct and avoids two tables drifting apart. Add a comment saying
so, since sharing looks like an accident otherwise.

### 4. Read `url` as a data-location key

`extractResourceSpecs` reads `datalocation` at `:351-353` and emits
`new ResourceSpec("DATA_LOCATION", path, false)`. Extend it to treat `url` the same way, mirroring
the existing pattern at `:358-360` where `schema` falls back to `database` for Trino.

Both keys should be accepted rather than one taking precedence: they come from different service
definitions (`datalocation` on lakeformation/hive, `url` on emr-spark/trino/presto), so a single
policy will only ever carry one of them in practice, and accepting both keeps the branch simple.

### What this does not change

- No production code under `src/`. The converter is already correct under the `all → ALL` decision.
- No EMRFS / S3 Access Grants modelling. The oracle has no `S3AccessGrantOperation` concept and
  EMRFS validation stays out of scope.
- No assessment-tool integration.

## Testing

Unit tests in the simulator project, one per divergence:

| Test | Asserts |
|---|---|
| LF-service `all` policy | exactly one permission, `ALL` — not the six-permission expansion |
| Trino `all` policy | exactly one permission, `ALL` |
| Trino `update`/`read`/`write` | map to `INSERT`/`SELECT`/`INSERT` |
| Presto policy | resolves to the same expectation as the identical Trino policy |
| `url` resource policy | yields `DATA_LOCATION` / `DATA_LOCATION_ACCESS` |
| **Trino `grant`, `revoke`, `execute`, `impersonate`** | contribute **no** permissions — guards the deliberate omission in §2 |

Each test must be observed failing before the corresponding change. The unmappable-types test will
pass beforehand; it is a regression guard, not a red-green test, and should be labelled as such.

Run the simulator suite with `cd simulator && mvn test` — it is a standalone Maven project with no
`<parent>`, so a root-level `mvn test` does not build it. 233 simulator tests currently pass.

## Expected fallout

Commit `9ca3daa` added simulator generators that exercise `all` policies. If those generators cover
Trino, divergence #2 means the simulator has been producing real under-grant violations, or masking
them. After this change the oracle's expectation moves, so the next live simulator run may surface
disagreements that were previously hidden on the oracle side.

That is the oracle working as intended. It should be expected rather than treated as a regression
introduced here — but it does mean the first post-change simulator run needs a human look rather
than a glance at the exit code.

## Files

| File | Change |
|---|---|
| `simulator/src/main/java/.../validator/ExpectedPermissionsComputer.java` | All four changes |
| `simulator/src/test/java/.../validator/ExpectedPermissionsComputerTest.java` | New tests |

## Follow-ups not in this spec

- **README** documents `all` as "Expanded to all applicable permissions" in its LakeFormation
  access-type mapping table. That contradicts `DESIGN.md` and the code. Needs correcting.
- **`EmrSparkServiceAdapter`** should be checked for an `all` mapping; its service definition
  declares no `all` access type, so any mapping there is dead or inconsistent.
- **Wiring the oracle into the assessment tool** — needs the `oracle/` module extraction; see
  project memory.
