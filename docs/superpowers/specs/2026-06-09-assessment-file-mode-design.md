# Assessment Tool: File Mode Enhancement

**Date:** 2026-06-09
**Status:** Approved

## Overview

Enhance the `assess` CLI tool to accept a pre-exported Ranger JSON file as a policy source, in addition to the existing live Ranger Admin connection. This enables offline assessment without network access to a Ranger Admin server.

The CLI is restructured around two explicit subcommands — `assess server` and `assess file` — replacing the previous flat syntax (breaking change).

---

## CLI

### New subcommand syntax

```
assess server [<config-file>] [options]

  --ranger-url <url>        Ranger Admin URL (required if no config file)
  --ranger-user <user>      Ranger Admin username
  --ranger-password <pass>  Ranger Admin password
  --services <s1,s2,...>    Comma-separated service instance names to assess
  --output-dir <dir>        Directory for JSON report (default: current dir)
  --aws-region <region>     Enable Glue wildcard expansion with this region
  --console-only            Print report to console only, skip JSON file

assess file <export-file.json> [options]

  --output-dir <dir>        Directory for JSON report (default: current dir)
  --aws-region <region>     Enable Glue wildcard expansion with this region
  --console-only            Print report to console only, skip JSON file
```

### Breaking change

The old flat `assess [<config-file>] [--ranger-url ...]` syntax is removed. `args[0]` must be `"server"` or `"file"`. Any other value (including a bare `--ranger-url` flag where the subcommand was forgotten) prints `USAGE` and exits with code 1.

The README must include an explicit migration callout with a before/after example so users with existing shell scripts can update them. See the Files Changed section.

### File mode service selection

In `assess file` mode, all services in the export file are assessed automatically. The `--services` flag is not supported; if provided, `parseFileArgs` must print a targeted message — `"--services is not supported in file mode; all services in the export are assessed automatically"` — and exit 1, rather than the generic unknown-flag error. Services with unrecognized service types (e.g. `yarn`, `kafka`) are skipped and reported as `UNSUPPORTED_SERVICE_TYPE` gap entries.

---

## Input: Ranger Export JSON Format

The Ranger Admin export JSON (obtained via the Ranger Admin UI export function) is a JSON object. The relevant structure is:

```json
{
  "policies": [
    {
      "id": 42,
      "name": "policy-name",
      "service": "hive_prod",
      "serviceType": "hive",
      "isEnabled": true,
      "policyType": 0,
      "resources": { ... },
      "policyItems": [ ... ],
      ...
    },
    ...
  ]
}
```

Key fields used:
- `service` — the Ranger service instance name (maps to `RangerServiceConfig.serviceInstanceName`)
- `serviceType` — the Ranger service type (used to select the `SourcePolicyAdapter`)
- All other fields are standard `RangerPolicy` fields already handled by the existing pipeline

Only the JSON format is supported. ZIP export bundles are not supported.

---

## New Components

### `PolicySource` interface (`assessment` package)

```java
public interface PolicySource {
    List<ServicePolicyBatch> load();
    String sourceLabel();
}
```

`load()` returns one `ServicePolicyBatch` per service found in the source. `sourceLabel()` returns a human-readable identifier used in console and JSON output.

### `ServicePolicyBatch` (`assessment` package)

Immutable value object:

| Field | Type | Description |
|---|---|---|
| `serviceName` | `String` | Ranger service instance name, e.g. `"hive_prod"` |
| `serviceType` | `String` | Ranger service type, e.g. `"hive"` |
| `policies` | `List<RangerPolicy>` | Enabled policies only (`isEnabled == true`); empty list if service is skipped |
| `rawPolicyCount` | `int` | Total policies found in the source before filtering (used for skip-reason messages) |
| `skipReason` | `String` | `null` if assessed; non-null description if skipped |

### `RangerAdminPolicySource` (`assessment` package)

Implements `PolicySource` for the live Ranger Admin connection.

- Wraps the existing `ConversionServerMain.fetchPoliciesFromRangerAdmin()` static method
- Constructor accepts `rangerAdminUrl`, `rangerUsername`, `rangerPassword`, and `List<RangerServiceConfig> services`; throws `IllegalArgumentException` if `rangerAdminUrl` is null or blank
- `sourceLabel()` returns `"ranger-admin:<rangerAdminUrl>"`
- One `ServicePolicyBatch` per configured service instance; fetch failures produce a batch with empty policies and a `skipReason`
- `rawPolicyCount` is set to the count returned before `isEnabled` filtering; `policies` contains only enabled policies (matching existing `fetchPoliciesFromRangerAdmin` behavior)

### `RangerExportFilePolicySource` (`assessment` package)

Implements `PolicySource` for the Ranger export JSON file.

- Accepts a `Path` to the export file
- Parses the file using `RangerExportModel` (see below) via an `ObjectMapper` configured with `FAIL_ON_UNKNOWN_PROPERTIES = false` (the export format contains many fields beyond what `RangerPolicy` maps)
- Filters out disabled policies (`isEnabled == false`) after parsing, before grouping; `rawPolicyCount` is set from the pre-filter count
- Groups policies by `service` (instance name) + `serviceType`
- Parses the file using UTF-8 encoding explicitly (`new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)`) to avoid platform-locale issues on Windows
- Policies with a null `service` or null `serviceType` field are skipped individually with a `WARN` log; they do not contribute to any batch's `rawPolicyCount` or `policies` list
- Checks each `serviceType` against the set of known types (`lakeformation`, `hive`, `presto`, `trino`, `amazon-emr-emrfs`); unrecognized types produce a skipped `ServicePolicyBatch` with the original `rawPolicyCount` populated so the skip-reason message can report how many policies were bypassed
- `sourceLabel()` returns `"file:<filename>"` (filename only, not full path)

### `RangerExportModel` (`assessment` package, package-private)

Thin Jackson deserialization model. Only maps the fields needed to build `ServicePolicyBatch` entries:

```java
class RangerExportModel {
    @JsonProperty("policies")
    List<RangerPolicy> policies;  // standard RangerPolicy — existing Jackson mapping reused
}
```

Service name and type are read directly from each `RangerPolicy`'s `service` and `serviceType` fields (already present on `RangerPolicy`).

---

## Modified Components

### `AssessmentRunner`

Signature change:

```java
// Before
public AssessmentResult run(AssessmentConfig config)

// After
public AssessmentResult run(AssessmentConfig config, PolicySource source)
```

Behavioral changes:
- `fetchPolicies(AssessmentConfig)` protected method removed
- `buildAdapterRegistry(AssessmentConfig)` private method removed — the registry is now built entirely from the `ServicePolicyBatch` list returned by `source.load()`
- `run()` calls `source.load()` to obtain `List<ServicePolicyBatch>`
- Skipped batches (non-null `skipReason`) produce one `UNSUPPORTED_SERVICE_TYPE` `GapEntry` each; `details` includes `batch.getRawPolicyCount()` so the message reads e.g. `"All 12 policies in this service are skipped."`
- Policies from skipped batches are excluded from `totalPolicies`, `fullyConvertible`, `partiallyConvertible`, and `notConvertible` counts
- Adapter registry is keyed on `serviceName` (the instance name, e.g. `"hive_prod"`), not `serviceType` — this matches `RangerToCedarConverter`'s lookup key which uses `policy.getService()` (the instance name). One adapter entry is created per non-skipped batch: `registry.put(batch.getServiceName(), createAdapter(batch.getServiceType()))`
- Passes `source.sourceLabel()` and the batch list into `AssessmentResult` constructor

The `protected convertToS3AgOps()` and `protected createS3AccessGrantsClient()` override points are retained unchanged for tests.

### `AssessmentConfig`

The `rangerAdminUrl` required-check in `build()` must be removed (currently at `AssessmentConfig.java:140`). Validation moves to `AssessmentMain` per subcommand: `parseServerArgs` validates that `rangerAdminUrl` is non-blank before constructing `RangerAdminPolicySource`; `parseFileArgs` never sets it.

### `AssessmentResult`

Two new fields added as constructor parameters to the existing `@JsonCreator` constructor (with `@JsonProperty` annotations, consistent with the immutable pattern of the class). Adding new fields to a `@JsonCreator` constructor is format-compatible for serialization (older readers ignore unknown fields). For deserialization of old JSON reports (which lack `"source"` and `"services"`), Jackson passes `null` for those parameters — the constructor must initialize `services` to `Collections.emptyList()` when `null` is received, to prevent `NullPointerException` in `AssessmentReporter` when iterating services.

| Field | Type | Description |
|---|---|---|
| `source` | `String` | Source label, e.g. `"ranger-admin:http://..."` or `"file:export.json"` |
| `services` | `List<AssessedService>` | One entry per `ServicePolicyBatch` |

**`AssessedService`** — new JSON-serializable value object (`@JsonInclude(NON_NULL)`):

| Field | Type | Description |
|---|---|---|
| `name` | `String` | Service instance name |
| `serviceType` | `String` | Ranger service type |
| `status` | `String` | `"assessed"` or `"skipped"` |
| `policiesScanned` | `int` | Policy count; `0` for skipped services |
| `skipReason` | `String` | `null` if assessed |

### `AssessmentMain`

`run(String[] args)` restructured:

```
args[0] == "server"  →  parseServerArgs(remaining)  →  build AssessmentConfig + RangerAdminPolicySource
args[0] == "file"    →  parseFileArgs(remaining)    →  build AssessmentConfig + RangerExportFilePolicySource
otherwise            →  print USAGE, exit 1
```

`USAGE` constant updated to document both subcommands. `applyConfigFile()` and `guessServiceType()` helpers are retained; `guessServiceType` is used only in server mode.

### `AssessmentReporter`

`printConsoleReport()` always renders the source/services preamble:

```
=== Apache Ranger → Lake Formation Assessment ===
Source:       <source label>
Assessed at:  <timestamp>

Services assessed:
  <name>   (<serviceType>)   — assessed  (<n> policies)
  <name>   (<serviceType>)   — skipped: <skipReason>

Policies scanned: ...
```

`writeJsonReport()` serializes the enriched `AssessmentResult` — `source` and `services` are included automatically via Jackson.

---

## Output Formats

### Console output (both modes)

```
=== Apache Ranger → Lake Formation Assessment ===
Source:       ranger-admin:http://ranger-admin:6080
Assessed at:  2024-06-01T10:30:00Z

Services assessed:
  lf_prod            (lakeformation)  — assessed  (31 policies)
  hive_prod          (hive)           — assessed  (16 policies)

Policies scanned:           47
  Fully convertible:        31 (66%)
  Partially convertible:    10 (21%)
  Not convertible:           6 (13%)
Projected LF grants:       142

Gaps detected (23 total):
  DATA_MASKING             :   5  — LF has no column masking. ...
  DENY_POLICY              :   8  — Deny rules are emitted as Cedar forbid statements; ...

Policies with gaps (13):
  Policy: mask-ssn (id=42)
    Resource: hr_db/employees
    [DATA_MASKING] Data masking policy (policyType=1) cannot be represented in Cedar.
      → Consider using column-level permissions or external masking solutions.
  ...

Full report written to: ./assessment-report-2024-06-01T10-30-00Z.json
```

In `assess file` mode, skipped services appear in the services table:

```
Services assessed:
  hive_prod          (hive)           — assessed  (16 policies)
  lf_prod            (lakeformation)  — assessed  (31 policies)
  yarn_prod          (yarn)           — skipped: unsupported service type
```

### JSON report

```json
{
  "source": "file:ranger-export-2024-06-01.json",
  "services": [
    { "name": "hive_prod",  "serviceType": "hive",           "status": "assessed", "policiesScanned": 16 },
    { "name": "lf_prod",    "serviceType": "lakeformation",  "status": "assessed", "policiesScanned": 31 },
    { "name": "yarn_prod",  "serviceType": "yarn",           "status": "skipped",  "policiesScanned": 0, "skipReason": "unsupported service type" }
  ],
  "totalPolicies": 47,
  "fullyConvertible": 31,
  "partiallyConvertible": 10,
  "notConvertible": 6,
  "projectedGrantCount": 142,
  "gapReport": {
    "entries": [
      {
        "policyId": "42",
        "policyName": "mask-ssn",
        "gapType": "DATA_MASKING",
        "resourcePath": "hr_db/employees",
        "details": "Data masking policy (policyType=1) cannot be represented in Cedar.",
        "recommendation": "Consider using column-level permissions or external masking solutions."
      },
      {
        "policyId": "17",
        "policyName": "deny-external-users",
        "gapType": "DENY_POLICY",
        "resourcePath": "finance_db/transactions",
        "details": "Policy contains deny items. Deny rules are emitted as Cedar forbid statements but cannot be enforced in Lake Formation (grant-only model).",
        "recommendation": "Review whether the deny can be replaced by removing the corresponding allow policy."
      },
      {
        "gapType": "UNSUPPORTED_SERVICE_TYPE",
        "details": "Service 'yarn_prod' (serviceType='yarn') has no registered adapter. All 12 policies in this service are skipped.",
        "recommendation": "Supported service types are: lakeformation, hive, presto, trino, amazon-emr-emrfs."
      }
    ],
    "summary": {
      "DATA_MASKING": 5,
      "DENY_POLICY": 8,
      "UNSUPPORTED_SERVICE_TYPE": 1
    },
    "generatedAt": "2024-06-01T10:30:00Z"
  }
}
```

---

## Error Handling

| Condition | Behavior |
|---|---|
| `assess file` — file not found or not readable | Print error to stderr, exit 1 |
| `assess file` — file is not valid JSON | Print parse error to stderr, exit 1 |
| `assess file` — file has no `"policies"` array | Treat as zero policies, warn to stderr, continue |
| `assess server` — `--ranger-url` missing and no config file | Print error + USAGE to stderr, exit 1 |
| `assess server` — Ranger Admin fetch failure for a service | Log warning; produce skipped `ServicePolicyBatch` with `skipReason`; continue with other services |
| Unknown subcommand | Print USAGE to stderr, exit 1 |
| Unknown flag within a subcommand | Print error + USAGE to stderr, exit 1 |

---

## Test Guidance

### `AssessmentRunnerTest` — migrate existing tests

All six existing tests in `AssessmentRunnerTest` inject policies by subclassing `AssessmentRunner` and overriding `fetchPolicies(AssessmentConfig)`. That method is removed. Each test must be rewritten to pass a `PolicySource` lambda or anonymous class as the second argument to `runner.run(config, source)`.

### New: `RangerExportFilePolicySourceTest`

Add unit tests covering:

| Scenario | Assertion |
|---|---|
| Well-formed export with two known service types and one unknown type | Two assessed batches; one skipped batch with correct `rawPolicyCount`; `UNSUPPORTED_SERVICE_TYPE` gap in result |
| Policies with `isEnabled=false` | Excluded from `policies`; counted in `rawPolicyCount` |
| Export with `"policies": null` or missing `"policies"` key | Zero policies, warning logged, no exception |
| Policy with null `service` or null `serviceType` field | Individual policy skipped with `WARN` log; not added to any batch |
| Export containing `amazon-emr-emrfs` service type | Assessed (not skipped) |
| Non-UTF-8 encoded file content | Parse error propagated as IOException |

### New: `AssessmentMainTest` — subcommand dispatch

- `assess server` with missing `--ranger-url` and no config file → exit 1
- `assess file` with `--services` flag → exit 1 with specific message
- `assess file` with non-existent file path → exit 1
- Unknown subcommand → exit 1 + USAGE

---

## Architecture Note

The `PolicySource` interface sits between `AssessmentMain` (which knows the source) and `AssessmentRunner` (which only cares about policies). `AssessmentRunner` does not reference `RangerAdminPolicySource`, `RangerExportFilePolicySource`, or `ConversionServerMain` directly — all source coupling moves to `AssessmentMain`. This also makes `AssessmentRunner` easier to test: inject a `PolicySource` stub rather than subclassing and overriding `fetchPolicies`.

The production `RangerToCedarConverter` and `CedarToLFConverter` pipeline is unchanged — assessment accuracy improves automatically when new service types or access type mappings are added to the sync pipeline.

---

## Files Changed

| File | Change |
|---|---|
| `assessment/PolicySource.java` | New interface |
| `assessment/ServicePolicyBatch.java` | New value object |
| `assessment/RangerAdminPolicySource.java` | New — wraps existing HTTP fetch |
| `assessment/RangerExportFilePolicySource.java` | New — reads export JSON |
| `assessment/RangerExportModel.java` | New — Jackson deserialization model (package-private) |
| `assessment/AssessedService.java` | New — JSON value object for per-service result |
| `assessment/AssessmentRunner.java` | Modified — accept `PolicySource`, remove `fetchPolicies()` |
| `assessment/AssessmentConfig.java` | Modified — remove required-check on `rangerAdminUrl` |
| `assessment/AssessmentResult.java` | Modified — add `source` and `services` fields |
| `assessment/AssessmentReporter.java` | Modified — always render source/services preamble |
| `app/AssessmentMain.java` | Modified — subcommand dispatch, new `USAGE`, new parse methods |
| `README.md` | Updated — (1) replace all flat `assess` CLI examples with `assess server` / `assess file` equivalents; (2) update the Pre-Migration Assessment Tool usage section; (3) add a "Obtaining a Ranger Export File" subsection explaining Ranger Admin UI export and that ZIP bundles must be extracted to JSON before use; (4) add a migration callout with before/after examples for users with existing scripts; (5) update the JSON report format example to include `source` and `services` fields; (6) update the `UNSUPPORTED_SERVICE_TYPE` row in the Gap Types table to clarify it appears at the service level in file mode |
| `test/.../AssessmentRunnerTest.java` | Modified — rewrite all six `fetchPolicies`-override tests to use `PolicySource` stubs |
| `test/.../RangerExportFilePolicySourceTest.java` | New — unit tests for file parsing, isEnabled filtering, null fields, unknown service types (see Test Guidance) |
| `test/.../AssessmentMainTest.java` | New or modified — subcommand dispatch tests |
