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

Note that `hiveMap.put("all", Set.of("ALL"))` is **already correct** — the expansion problem is
confined to `lfMap`.

**Divergence #6 was withdrawn.** An earlier revision claimed the oracle's SELECT-only column
filter diverged from the adapters' `COLUMN_VALID_ACTIONS = {SELECT, ALL}`. Direct testing against
the Lake Formation API (account 106567286946, us-west-2, 2026-09-30) showed the **adapters** are
wrong, not the oracle: a `TableWithColumns` grant carrying `ALL` is rejected with
`InvalidInputException: Permissions modification is invalid`, while the same grant with `SELECT`
succeeds. Retested with the control revoked and on an untouched column — rejected identically all
three times. The oracle's SELECT-only filter was correct all along, so §4/§4a are gone and a
production fix (§6) takes their place.

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
definitions (`datalocation` on lakeformation/hive, `url` on emr-spark and EMR-flavoured trino), so no
bundled servicedef declares both and a single policy will not carry both in practice.

One inconsistency to acknowledge rather than leave implicit: production's `determineResourceLevel`
(`RangerToCedarConverter.java:551-568`) checks `url` **before** `datalocation`, whereas mirroring the
existing `schema`→`database` fallback pattern checks the primary key first. Since no servicedef
declares both, the orders cannot diverge in practice — but match production's `url`-first ordering
anyway, so the two implementations do not differ for a reason nobody can later reconstruct.

### 5. Production fix: narrow column-scoped `all` to `SELECT`

Not an oracle change — a live converter defect found while verifying the withdrawn divergence #6.

**Verified API behaviour** (account 106567286946, us-west-2, 2026-09-30):

| Grant | Result |
|---|---|
| `TableWithColumns` + `SELECT` | accepted |
| `TableWithColumns` + `ALL` | `InvalidInputException: Permissions modification is invalid` |
| same, control revoked first | rejected identically |
| same, on a column never granted | rejected identically |

So `ALL` is invalid on a column-restricted resource. Two code comments already asserted this
(`LakeFormationClient.java:259`, `SyncService.java:1018` — "TWC grants are SELECT-only"); the
`COLUMN_VALID_ACTIONS = Set.of("SELECT", "ALL")` constant in all five adapters contradicted them.

**Current behaviour, both under-grants, neither an escalation.** Nothing folds TWC permissions onto
the TABLE resource: `losingOps` is a removal set filtered out at `SyncService:1072`, and
`CedarToLFConverter`'s merge key includes both resource and permission set by design (`:243`).

1. Column-scoped `all` alone → `(TWC, ALL)` → LF rejects → dead-letter. Policy grants nothing.
2. Column-scoped `all` plus a TABLE `SELECT` from another policy → Rule B discards the whole TWC op
   → policy grants nothing, silently, no dead-letter entry.

**Fix — map, do not filter.** Changing `COLUMN_VALID_ACTIONS` to `Set.of("SELECT")` alone is
**wrong**: the adapters map `all` → `{ALL}` first, so filtering `{ALL}` through `{SELECT}` yields
*empty* and the policy still produces nothing. The column branch must map explicitly:

```java
if ("column".equals(resourceLevel)) {
    Set<String> raw = mapAccessTypeToCedarActions(sourceAccessType);
    if (raw.contains("ALL")) {
        // LF rejects ALL on a column-restricted resource (verified against the API). Narrow to
        // the one permission LF accepts here rather than dropping the grant entirely.
        return Collections.singleton("SELECT");
    }
    return raw.stream().filter(COLUMN_VALID_ACTIONS::contains)
              .collect(java.util.stream.Collectors.toSet());
}
```

with `COLUMN_VALID_ACTIONS = Set.of("SELECT")`. Apply to all five adapters
(`RangerServiceAdapter`, `HiveServiceAdapter`, `TrinoServiceAdapter`, `PrestoServiceAdapter`,
`EmrSparkServiceAdapter`).

This repairs both cases: case 1's grant now lands, and case 2's TWC entry becomes a genuine `SELECT`
that a table-wide `SELECT` legitimately subsumes — making Rule B's stated justification true again.

**Correction (2026-09-30, follow-up):** the plan below — reusing `KNOWN_UNMAPPABLE_ACTION` — is
what was actually built first (commits `cf92fbf`, `8ba7125`, `5bf4d41`, `be7dce7`), and it turned
out to be the wrong call in practice. A dedicated `GapType.NARROWED_ACTION` was introduced
immediately after (same day) to replace it. Keeping the original reasoning below for the record,
followed by why it changed.

**Original plan: report the narrowing as `KNOWN_UNMAPPABLE_ACTION`.** Reuse the existing type
rather than adding one: it is informational and non-demoting, which is the property that
matters. Detect it in `RangerToCedarConverter.extractCedarActions` by comparing the raw and
level-aware mappings — raw contains `ALL` while the level-aware result is `{SELECT}` means
narrowing occurred. No adapter API change needed.

Semantic stretch to acknowledge in the details text: the existing wording for this gap type says
access types "have no Lake Formation equivalent and were dropped", which is not quite true here —
`all` was *narrowed*, not dropped. Word this entry specifically, e.g. "Access type 'all' on a
column-restricted resource was narrowed to SELECT — Lake Formation accepts only SELECT on
TABLE_WITH_COLUMNS resources."

**Why this changed:** reuse worked at the `GapEntry` level but broke down one layer up. The
assessment rollup (`KnownUnmappableActionSummary`, `AssessmentRunner.buildKnownUnmappableRollup`,
the console's "Known-unmappable access types ... no Lake Formation equivalent" section header)
only carries `(serviceType, accessType, occurrenceCount, samplePolicyIds)` — no field for *why*.
Feeding a narrowed-but-landed access type into that rollup produced a console section whose own
header ("no Lake Formation equivalent") contradicted the row directly under it (`hive: all`,
narrowed not dropped), with no visible explanation of what actually happened. Worse, because
narrowing is non-demoting and the grant lands, a policy affected only by narrowing is fully
convertible and never appears in the gaps file's per-policy `policies` array — so the rollup was
the *only* place the narrowing was ever visible, and that one place was actively misleading.

The fix was a parallel `GapType.NARROWED_ACTION` with its own rollup, `NarrowedActionSummary`,
carrying two fields `KnownUnmappableActionSummary` does not have: `narrowedTo` (the permission
actually granted) and `reason` (why the broader permission was rejected). Both are read from
`RangerToCedarConverter.NARROWED_ACTION_TARGET` / `NARROWED_ACTION_REASON` — constants beside the
emission site — so the assessment layer never has to (re-)invent or hardcode the explanation
independently of the code that knows it. `KnownUnmappableActionSummary` itself was deliberately
left untouched rather than generalised into a shared shape with the new type — see
`docs/design/09-models-and-reporting.md`.

**Priority note: unreachable in current data.** Scanned `Dev_Ranger_Hive_Policies.json`,
`Dev_Ranger_Trino_Policies.json` and the 6,000-policy `Ranger_Policies_sample.json` — **zero**
policies use specific column names at all, let alone with `all`. This is a latent correctness fix,
not a live incident. It is on this branch because it came out of the same investigation.

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
| **Column-scoped `all` (adapter, §5)** | maps to `SELECT`, **not** empty — guards against the filter-only mistake |
| Column-scoped `insert`/`alter` (adapter, §5) | still yield nothing — the filter was narrowed, not removed |
| Column-scoped `all` end-to-end | produces `(TABLE_WITH_COLUMNS, SELECT)` and one `NARROWED_ACTION` gap recording the narrowing (originally `KNOWN_UNMAPPABLE_ACTION`; corrected in the same-day follow-up — see the note above) |

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
| `simulator/src/main/java/.../validator/ExpectedPermissionsComputer.java` | §1–§4 (oracle) |
| `src/main/java/.../ranger/{Ranger,Hive,Trino,Presto,EmrSpark}ServiceAdapter.java` | §5 column-level `all` → `SELECT` |
| `src/main/java/.../ranger/RangerToCedarConverter.java` | §5 narrowing gap detection |
| `src/test/java/.../ranger/*AdapterTest.java`, `RangerToCedarConverterTest.java` | §5 tests |
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
