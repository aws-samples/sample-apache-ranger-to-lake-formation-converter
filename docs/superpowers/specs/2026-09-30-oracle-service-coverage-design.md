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
| 1 | `all` on `lakeformation` | `ExpectedPermissionsComputer.java:562` expands to `{SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE}` | `RangerServiceAdapter` emits `ALL` | Inconsistent with `hiveMap` in the same file. **No real-world exposure** — `conf/ranger-servicedef-lakeformation.json` declares no `all` access type, so Ranger Admin cannot issue such a policy. Defensive consistency fix only. |
| 2 | `all` on `trino` | **absent** from `trinoMap` (starts `:581`) | `TrinoServiceAdapter` emits `ALL` | Oracle expects zero permissions where the converter grants `ALL` — a false under-grant |
| 3 | `read`, `write`, `update` on `trino` | absent from `trinoMap` | mapped to `SELECT`, `INSERT`, `INSERT` | False under-grants. `read`/`write` are real (used on `url` policies in `amazon-emr-trino.json`); `update` is declared by **neither** bundled Trino servicedef and is code-parity only |
| 4 | `presto` service type | **not registered** at `:604-607` | `PrestoServiceAdapter` fully supported | Narrower than it looks: `accessMapForService` (`:536-546`) falls unregistered services back to `lfMap` wholesale, and `lfMap` happens to share most keys with `PrestoServiceAdapter`. So `select/insert/delete/create/drop/alter/read/write` already work **by coincidence**. What genuinely breaks: `use`/`show` (Presto maps both to `DESCRIBE`; `lfMap` has no such keys, so they return null and vanish), plus `all` inheriting divergence #1's wrong expansion |
| 5 | `url` resource key | only `datalocation` is read (`:351-353`) | `url` → `DATA_LOCATION_ACCESS` | False under-grants on every `url` policy. Scope note: only `amazon-emr-trino.json` and `amazon-emr-spark.json` declare a `url` resource; the standalone `trino.json`/`presto.json` do not |
| 6 | **`ALL` at column level** | `:285-288` hardcodes `permissions.contains("SELECT") ? Set.of("SELECT") : Set.of()` | All five adapters use `COLUMN_VALID_ACTIONS = Set.of("SELECT", "ALL")` and emit `(TABLE_WITH_COLUMNS, ALL)` | A column-scoped `all` policy yields **zero** oracle permissions while the converter grants `ALL`. Latent today; becomes reachable the moment §1/§2 make `all` map to `{ALL}` |

Note that `hiveMap.put("all", Set.of("ALL"))` is **already correct** — the expansion problem is
confined to `lfMap`.

Divergence #6 was found during spec review. It is the only one that is *created* rather than merely
exposed by this spec's other changes: today a column-level `all` produces the six-permission
expansion, which contains `SELECT`, so the oracle emits `SELECT` and coincidentally half-agrees.
Once `all` maps to `{ALL}`, `permissions.contains("SELECT")` is false and the oracle emits nothing.
Fixing #1 and #2 without #6 would therefore make the oracle *worse* for column-scoped policies.

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

### 4. Accept `ALL` at column level

At `:285-288` the column branch reads:

```java
finalPerms = permissions.contains("SELECT") ? Set.of("SELECT") : Set.of();
```

Replace the hardcoded SELECT-only filter with the same `{SELECT, ALL}` set the production adapters
use, so a column-scoped `all` policy yields `ALL` rather than nothing:

```java
// LF TABLE_WITH_COLUMNS accepts SELECT and ALL — mirrors COLUMN_VALID_ACTIONS in every
// production adapter. INSERT/DELETE/ALTER on a column resource remain ignored.
finalPerms = permissions.stream()
        .filter(COLUMN_VALID_PERMISSIONS::contains)
        .collect(Collectors.toUnmodifiableSet());
```

with `private static final Set<String> COLUMN_VALID_PERMISSIONS = Set.of("SELECT", "ALL");`.

Note this is the one change that must land **together with** §1 and §2, not after: fixing `all`
without fixing this makes column-scoped policies worse, for the reason given under the divergence
table. Sequence the commits so no intermediate commit regresses.

Update the existing comment, which currently asserts "LF TABLE_WITH_COLUMNS only supports SELECT" —
that is the claim being corrected.

#### §4a — widen `isTwcPerm`, or §4 introduces a false over-grant

`isTwcPerm` at `:486-490` currently requires the permission to be exactly `SELECT`:

```java
private static boolean isTwcPerm(SimulatorPermission p) {
    return "TABLE_WITH_COLUMNS".equals(p.resourceType())
            && "SELECT".equals(p.permission())
            && !p.resourceId().endsWith(".*");
}
```

Pass 3 (`:133-177`) uses that predicate, plus `"TABLE".equals(p.resourceType())`, to sort every
permission into `twcMinId` or `tableMinId` before applying the TABLE/TWC coexistence rule — a rule
the class comment states holds "regardless of which permissions are involved", mirroring
`SyncService.detectAndGapTableTwcConflicts`.

Once §4 lands, a column-scoped `all` produces `(TABLE_WITH_COLUMNS, ALL)`. That satisfies **neither**
branch at `:140-147`, so it enters neither map, never reaches `conflictKeys`, and is silently exempt
from conflict resolution. Concretely: if the same `(principal, table)` also has a TABLE-level grant
from another policy — precisely what Pass 3 exists to resolve — the `ALL` entry survives no matter
which policy id wins, where the equivalent `(TABLE_WITH_COLUMNS, SELECT)` entry would correctly be
removed. That is a false **over**-grant, introduced by §4.

Fix: drop the permission check so any non-wildcard `TABLE_WITH_COLUMNS` entry is TWC-class.

```java
private static boolean isTwcPerm(SimulatorPermission p) {
    // Any permission, not just SELECT: a column-scoped `all` yields (TABLE_WITH_COLUMNS, ALL),
    // and the TABLE/TWC coexistence rule is permission-independent.
    return "TABLE_WITH_COLUMNS".equals(p.resourceType())
            && !p.resourceId().endsWith(".*");
}
```

This must land in the **same commit** as §4. §4 alone is a regression.

Add a test: a column-scoped `all` policy coexisting with a TABLE-level grant on the same
`(principal, table)` from a different policy id, asserting the conflict is resolved exactly as the
`SELECT` equivalent would be.

### 5. Read `url` as a data-location key

`extractResourceSpecs` reads `datalocation` at `:351-353` and emits
`new ResourceSpec("DATA_LOCATION", path, false)`. Extend it to treat `url` the same way, mirroring
the existing pattern at `:358-360` where `schema` falls back to `database` for Trino.

Both keys should be accepted rather than one taking precedence: they come from different service
definitions (`datalocation` on lakeformation/hive, `url` on emr-spark and EMR-flavoured trino), so no
bundled servicedef declares both and a single policy will not carry both in practice.

One inconsistency to acknowledge rather than leave implicit: production's `determineResourceLevel`
(`RangerToCedarConverter.java:551-568`) checks `url` **before** `datalocation`, whereas mirroring the
existing `schema`→`database` fallback pattern checks the primary key first. Since no servicedef
declares both, the orders cannot diverge in practice — but match production's `url`-first ordering
anyway, so the two implementations do not differ for a reason nobody can later reconstruct.

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
| Presto `use`/`show` policy | map to `DESCRIBE` — the case that genuinely breaks today, since the `lfMap` fallback has no such keys |
| **Column-scoped `all` policy** | yields `(TABLE_WITH_COLUMNS, ALL)`, not zero permissions — covers divergence #6 |
| Column-scoped `insert`/`alter` policy | still yields **nothing** — confirms §4 widened the filter to `{SELECT, ALL}` only, and did not remove it |
| **Trino `grant`, `revoke`, `execute`, `impersonate`** | contribute **no** permissions — guards the deliberate omission in §2 |
| **Column-scoped `all` + TABLE grant on the same (principal, table)** | conflict resolved identically to the `SELECT` equivalent — covers §4a |

Each test must be observed failing before the corresponding change, with two exceptions to label
explicitly as regression guards rather than red-green tests:

- the unmappable-types test passes beforehand;
- the column-scoped `all` test passes beforehand *for the wrong reason* — today `all` expands to a
  set containing `SELECT`, so the old filter emits `SELECT`. Assert on `ALL` specifically, not merely
  "non-empty", or it will silently keep passing across the change while testing nothing.

Run the simulator suite with `cd simulator && mvn test` — it is a standalone Maven project with no
`<parent>`, so a root-level `mvn test` does not build it. 233 simulator tests currently pass.

## Expected fallout: none for live simulator runs

An earlier draft of this spec speculated that commit `9ca3daa` ("exercise all-access, isExcludes and
denyExceptions policies") meant live runs were already hitting these divergences. **That is wrong**,
verified during review:

- `simulator/src/main/java/.../workload/TrinoServiceGenerator.java:13-14` declares
  `TRINO_ACCESS_TYPES = List.of("select", "insert", "delete", "create", "drop", "alter", "use", "show")`
  — it never emits `all`, `read`, `write` or `update`.
- There is **no Presto generator** anywhere under `workload/`.
- `9ca3daa` added only a *hive* `all` generator; its own message notes that the lakeformation
  servicedef rejects `all`.

So divergences #2, #3, #4 and #6 have never been exercised by a live simulator run, and still will
not be after this lands. Nothing about live-run behaviour changes here.

**This is itself a finding:** the oracle fixes are unverifiable end-to-end until the generators emit
these access types and a Presto service. Extending the generators is deliberately **out of scope**
here — the unit tests in this spec are what prove the mapping — but it should be queued as the
natural follow-on, otherwise this work is only covered by unit tests that assert the oracle matches
a table we wrote from the same reading of the adapters.

## Files

| File | Change |
|---|---|
| `simulator/src/main/java/.../validator/ExpectedPermissionsComputer.java` | All four changes |
| `simulator/src/test/java/.../validator/ExpectedPermissionsComputerTest.java` | New tests, **and rewrite `columnLevelGrantProducesOnlySelect` (`:781-793`) in place** |

### One existing test must be rewritten, not supplemented

`ExpectedPermissionsComputerTest.columnLevelGrantProducesOnlySelect` (`:781-793`) is exactly the
divergence-#6 shape: `service="lakeformation"`, access `"all"`, column-scoped. It asserts
`assertEquals("SELECT", perm.permission())`, which becomes `ALL` after §1 and §4.

Rewrite it in place — rename it, flip the assertion, and update the stale section comment at
`:776-778` ("LF TABLE_WITH_COLUMNS only supports SELECT"). Do **not** add the new column-`all` test
alongside it, or the suite ends up with two tests asserting opposite results for identical input.

## Follow-ups not in this spec

- **README** documents `all` as "Expanded to all applicable permissions" in its LakeFormation
  access-type mapping table. That contradicts `DESIGN.md` and the code. Needs correcting.
- **`EmrSparkServiceAdapter`** should be checked for an `all` mapping; its service definition
  declares no `all` access type, so any mapping there is dead or inconsistent.
- **Extend the simulator generators** to emit `all`, `read`, `write` and a Presto service, so these
  oracle fixes are verifiable end-to-end rather than only by unit test. See the fallout section for
  why this matters: without it, nothing exercises divergences #2, #3, #4 or #6 in a live run.
- **Wiring the oracle into the assessment tool** — needs the `oracle/` module extraction; see
  project memory.
