# Comprehensive Testing Strategy Design

**Date:** 2026-05-26  
**Status:** Approved

---

## Problem Statement

ApacheRangerToLF is a security product that continuously syncs Apache Ranger access policies to AWS Lake Formation permissions. Correctness is critical: a missed revoke means a user retains data access they shouldn't have; a missed grant means authorized users are locked out. The existing test suite has strong unit and integration coverage, but several security-critical scenarios are untested, and there is no long-running validation against a real AWS environment.

This design covers two tracks:
1. **Track 1**: Fill identified security-critical gaps in the existing test suite
2. **Track 2**: A real-world continuous simulator that mutates Ranger policies against a dedicated AWS account and validates that Lake Formation permissions remain correct at all times

---

## Track 1: Missing Security-Critical Tests

### Unit and Property Tests

#### `CedarToLFConverterTest.java` (extend existing)
- **Cross-policy forbid suppression**: a `forbid` from policy A suppresses a `permit` from policy B for the same (principal, action, resource) — assert zero `LFPermissionOperation` entries produced
- **Deny-exception specificity**: a `@denyException` for (action1, resource1) does NOT restore a permit for (action2, resource2) where no deny-exception exists

#### New: `CedarToLFConverterCrossPolicyPropertyTest.java`
- **Property**: for any combination of permit + forbid across N policies for the same (principal, action, resource) triple, if any forbid exists with no matching deny-exception, the output contains zero grants for that triple
- Uses jqwik with `@Property(tries = 100)` and custom `@Provide` arbitraries for Cedar policy sets

#### `PolicyConverterTest.java` (extend existing)
- **Deny-only policy**: a Ranger policy with only deny items produces zero `LFPermissionOperation` entries (currently only the gap record is asserted, not the absence of grants)

#### New: `PipelineUnmappedPrincipalTest.java`
- **All-unmapped principals**: all users in a policy item are unmapped by `PrincipalMapper` → zero grants produced, no exception thrown, gap recorded
- Asserted at the full pipeline level: Ranger `RangerPolicy` → `LFPermissionOperation` list

#### `SyncServiceTest.java` (extend existing)
- **grantable=true propagation**: a Ranger policy with delegated admin flag set flows through to `isGrantable=true` on the resulting `LFPermissionOperation`
- **grantable=false default**: a Ranger policy without the delegated admin flag produces `isGrantable=false` — guards against a constructor regression that would silently over-provision grant authority to every synced principal

#### New: `RowFilterPassThroughTest.java`
- **Row filter safety**: row filter expressions containing SQL metacharacters (`'`, `"`, `;`, `--`, `\n`) and null bytes are passed to the LF client with byte-equality to the input string — verified by asserting the expression field of the resulting `LFPermissionOperation` equals the input, not merely that no exception is thrown
- **Overlong filter behavior**: a row filter expression longer than 4096 characters must either (a) produce a gap record and zero grants (preferred — prevents hitting downstream API limits), or (b) pass through verbatim if LF imposes no limit; the expected behavior must be chosen and explicitly asserted — "no crash" is not sufficient

### Integration Tests

All new integration tests follow the existing `DryRunPipelineIT` pattern: in-process pipeline, Dockerized Ranger Admin, dry-run JSON output assertions.

#### New: `CrossPolicyDenyInteractionIT.java`
- Create policy A: grants SELECT on `db.table` to `analyst`
- Create policy B: denies SELECT on `db.table` to `analyst`
- Assert dry-run output contains **zero grants** for `analyst` on `db.table`

#### New: `WildcardRevocationOnTableRemovalIT.java`
- Create wildcard policy: grants SELECT on `db.*` to `etl_user`
- Cycle 1: assert grants exist for all tables returned by the passthrough `CatalogResolver` (e.g., `table1`, `table2`, `table3`)
- Reconfigure `CatalogResolver` to return `table1`, `table2` only (simulates table removal from Glue)
- Cycle 2: assert grant for `table3` is **REVOKED**, grants for `table1` and `table2` still exist

#### New: `GrantablePermissionPropagationIT.java`
- Create Ranger policy with delegated admin flag set for `data_admin` on `db.table`
- Assert dry-run JSON output contains `permissionsWithGrantOption` populated for the (data_admin, db.table, SELECT) triple

---

## Track 2: Real-World Simulator

### Overview

A long-running process (days) in a **dedicated AWS account** that:
1. Continuously generates and applies realistic Ranger policy mutations
2. Waits for the sync service to process each mutation
3. Validates that actual LF permissions match expected state
4. On violation: attempts remediation, writes a full reproduction bundle, and continues

### Architecture

#### New Maven Module: `simulator/`

Added to the root `pom.xml` as a sibling module. Package root: `com.amazon.apacherangertoLF.simulator`.

```
simulator/
  pom.xml
  src/main/java/.../simulator/
    workload/
      HivePolicyGenerator.java
      DataLocationPolicyGenerator.java
      TagPolicyGenerator.java
      WorkloadOrchestrator.java
      MutationOperation.java        (sealed: CREATE, UPDATE, DISABLE, ENABLE, DELETE)
      MutationLog.java
    driver/
      SimulatorMain.java             — runs startup assertions before mutation loop
      SimulatorCleanup.java          — standalone cleanup main: delete all simulator Ranger policies,
                                       wait for sync, assert LF empty, revoke any residue
      SimulatorConfig.java
      MutationDriver.java
      RangerPolicyClient.java
    status/
      SyncServiceStatusClient.java
      CycleWaiter.java
    validator/
      Phase1DriftValidator.java
      Phase2CorrectnessValidator.java
      ExpectedPermissionsComputer.java   ← ZERO imports from main conversion pipeline
                                           output type is SimulatorPermission, not LFPermissionOperation
      SimulatorPermission.java           ← internal value type: principalArn, resourcePath, permissionSet, isGrantable
      LFPermissionsFetcher.java          ← new independent implementation using AWS SDK v2 directly
                                           does NOT wrap production LFPermissionFetcher
                                           normalizes results to SimulatorPermission for comparison
      ValidationResult.java
    remediation/
      RemediationRunner.java
      ReproductionBundle.java
      BundleWriter.java
    alert/
      AlertEmitter.java              (interface)
      LogFileAlertEmitter.java
      CloudWatchAlertEmitter.java
      SnsAlertEmitter.java
  src/test/java/.../simulator/
    ExpectedPermissionsComputerTest.java
    WorkloadOrchestratorTest.java
```

### Sync Service Changes

#### New: `StatusEndpoint.java`

Lightweight HTTP endpoint (added to existing HTTP server or new embedded server):

```
GET /status
→ {
    "lastCompletedCycle": <long>,
    "wildcardRefreshActive": <boolean>,
    "state": "running"
  }
```

- `lastCompletedCycle`: monotonic counter, incremented at the end of each successful `SyncService.runCycle()` call
- `wildcardRefreshActive`: flag set/cleared by `WildcardRefreshScheduler` around active refresh execution

#### Modify: `SyncService.java`
- Add `AtomicLong lastCompletedCycle` field
- Increment at end of each successful cycle
- Expose via `StatusEndpoint`

#### Modify: `WildcardRefreshScheduler.java`
- Add `AtomicLong lastCompletedWildcardRefreshCycle` field (monotonic, NOT a boolean flag)
- Increment in the `finally` block of `executeWildcardRefresh()` after each completed refresh
- Expose via `StatusEndpoint`
- **Rationale**: a boolean `wildcardRefreshActive` flag has a race window — the main cycle counter can increment and the simulator can observe `active=false` in the gap before the next refresh starts. A monotonic counter lets `CycleWaiter` snapshot `W` before mutations and wait until `lastCompletedWildcardRefreshCycle >= W`, eliminating the race.

### Simulator Flow (per cycle)

```
1.  N = GET /status → lastCompletedCycle
    W = GET /status → lastCompletedWildcardRefreshCycle
2.  WorkloadOrchestrator generates mutation batch (1–5 operations)
3.  MutationDriver applies batch to Ranger REST API
4.  MutationLog.append(timestamp, operations, full policy payloads)
5.  CycleWaiter.waitUntil(
        lastCompletedCycle > N
        AND lastCompletedWildcardRefreshCycle >= W,
        pollInterval = 2s,
        timeout = 3 × policyRefreshIntervalMs
    )
    On timeout: log CYCLE_WAIT_TIMEOUT, skip validation for this iteration, continue
6.  Phase1DriftValidator.validate()
    → fetch sync service checkpoint
    → paginated ListPermissions for all principals in IAM pool
    → diff: checkpoint vs. actual LF
7.  Phase2CorrectnessValidator.validate()
    → fetch all current Ranger policies via REST
    → ExpectedPermissionsComputer.compute(policies)  ← independent, no pipeline imports
    → diff: expected vs. actual LF (using SimulatorPermission type, not LFPermissionOperation)
8.  If PASS: log success, proceed to next cycle
9.  If violation:
    a. BundleWriter.write(ReproductionBundle) to reproduction-bundles/<timestamp>/
    b. RemediationRunner.run():
       - record M = GET /status → lastCompletedCycle
       - wait for lastCompletedCycle > M (same CycleWaiter logic, same timeout)
       - re-run Phase1 + Phase2
    c. If self-healed:
       - log TRANSIENT_VIOLATION with bundle path
       - AlertEmitter.emit(TRANSIENT, bundle)
    d. If not healed:
       - log PERSISTENT_VIOLATION with bundle path
       - AlertEmitter.emit(PERSISTENT, bundle)
    e. Continue to next cycle in both cases
```

### Workload Design

**Principal pool** (pre-provisioned IAM roles in the test account, reusing integration test names):
- `analyst`, `etl_user`, `data_admin`, `viewer` → IAM role ARNs

**Glue catalog** (pre-populated):
- 3 databases (`finance`, `marketing`, `ops`), ~10 tables each, representative column sets

**Mutation weights:**

| Operation | Weight | Covers |
|-----------|--------|--------|
| Create new policy | 30% | Fresh grants, wildcard expansion |
| Add/remove principal | 25% | Incremental diff |
| Update permissions | 20% | SELECT → SELECT+INSERT transitions |
| Disable/re-enable policy | 15% | Disabled-policy revocation |
| Delete policy | 10% | Full revocation |

**Policy type mix:**
- ~70% Hive/Trino (database, table, column grants)
- ~20% Data location / EMRFS S3 Access Grants
- ~10% Tag-based (always produces gaps, never LF grants)

**Negative case injection (within create operations):**
- ~20% of creates are deny policies alongside allows on the same resource
- ~10% of creates are all-unmapped-principal policies
- Periodic overlapping column grants from two separate policies

### Startup Phase

Before beginning the mutation loop, `SimulatorMain` must verify the environment is clean:

1. Assert Ranger Admin has zero policies for the sync service's configured plugin names (hard error if not — operator must clean up manually)
2. Assert LF has zero permissions for all principals in the IAM pool (hard error if not)
3. Wait for the sync service to complete one full cycle (`CycleWaiter` with `lastCompletedCycle > 0`)
4. Re-assert LF has zero permissions (confirms sync service did not apply stale state)
5. Only then begin the mutation loop

If the simulator crashes mid-run and is restarted, it re-runs this startup phase. If the assertions fail (Ranger and/or LF are in a non-empty state), it logs `DIRTY_STARTUP` and exits — the operator must run `SimulatorCleanup` before restarting. There is no automatic resume from a partial state.

### Cleanup

`SimulatorCleanup` is a separate main class that:
1. Deletes all Ranger policies created during the run (using the `MutationLog` to identify them, or by listing all policies with the simulator's tag/prefix)
2. Waits for the sync service to process the deletions (`CycleWaiter`)
3. Asserts LF permissions for the IAM pool are now zero — logs any remaining permissions as `CLEANUP_RESIDUE` and attempts a direct LF revoke for each
4. Must be run as the final step of every simulator session, including abnormal terminations

### Reproduction Bundle

Written to `reproduction-bundles/<ISO-timestamp>/`:

| File | Contents |
|------|----------|
| `mutations.json` | Full mutation log from run start to violation |
| `ranger-snapshot.json` | All current Ranger policies at time of violation |
| `lf-actual.json` | Full `ListPermissions` output |
| `lf-expected.json` | Expected permissions from Phase 2 independent computation |
| `diff.json` | Structured diff: over-grants, under-grants, missed-revokes |
| `sync-service.log` | Sync service log lines from last N cycles |
| `cycle-sequence.json` | `{ violationDetectedAfterCycle: N, lastSuccessfulCycle: N-1 }` |
| `README.txt` | Human-readable step-by-step reproduction instructions |

---

## Files to Create / Modify

### Main source

| File | Action |
|------|--------|
| `pom.xml` (root) | Add `<module>simulator</module>` |
| `simulator/pom.xml` | New module POM |
| `simulator/src/main/java/.../simulator/**` | New: all simulator classes including `SimulatorCleanup` |
| `src/main/java/.../sync/SyncService.java` | Add `lastCompletedCycle` counter, increment per cycle |
| `src/main/java/.../sync/WildcardRefreshScheduler.java` | Add `lastCompletedWildcardRefreshCycle` monotonic counter |
| `src/main/java/.../...StatusEndpoint.java` | New: `GET /status` HTTP endpoint |

### Test source

| File | Action |
|------|--------|
| `src/test/java/.../cedar/CedarToLFConverterTest.java` | Add cross-policy forbid + deny-exception specificity tests |
| `src/test/java/.../cedar/CedarToLFConverterCrossPolicyPropertyTest.java` | New |
| `src/test/java/.../PolicyConverterTest.java` | Add deny-only → zero grants test |
| `src/test/java/.../PipelineUnmappedPrincipalTest.java` | New |
| `src/test/java/.../sync/SyncServiceTest.java` | Add grantable=true propagation test |
| `src/test/java/.../RowFilterPassThroughTest.java` | New |
| `src/test/java/.../integration/CrossPolicyDenyInteractionIT.java` | New |
| `src/test/java/.../integration/WildcardRevocationOnTableRemovalIT.java` | New |
| `src/test/java/.../integration/GrantablePermissionPropagationIT.java` | New |

---

## Verification

### Track 1
- `mvn test` — all new unit/property tests pass
- `mvn verify` (with Dockerized Ranger) — all three new IT classes pass

### Track 2
- `mvn test -pl simulator` — `ExpectedPermissionsComputerTest` and `WorkloadOrchestratorTest` pass
- **Smoke run (1 hour)**: simulator completes 20+ cycles, zero persistent violations, at least 2 transient violations self-heal and produce reproduction bundles with correct contents
- **Fault injection — over-grant**: manually grant an extra LF permission out-of-band → validator detects over-grant within one cycle, remediation re-run clears it, TRANSIENT_VIOLATION bundle written
- **Fault injection — under-grant**: manually revoke a permission the sync service should maintain → PERSISTENT_VIOLATION detected if sync service does not re-grant it within one cycle
- **Multi-day run**: simulator runs 72+ hours with mixed workload, zero persistent violations
- **Cleanup**: `SimulatorCleanup` runs after every session (normal and abnormal termination), asserts LF permissions for the IAM pool are zero after completion

### Implementation Order
1. Track 1 unit/property tests
2. Track 1 integration tests
3. Sync service `StatusEndpoint` + `lastCompletedCycle` + `wildcardRefreshActive`
4. Simulator `workload/` + `driver/` (smoke-runnable without validators)
5. Phase 1 drift validator
6. Phase 2 correctness validator + `ExpectedPermissionsComputer`
7. Remediation + reproduction bundle writer
8. Alert emitter (log-file first, CloudWatch/SNS as follow-on)
