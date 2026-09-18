# Ranger → Lake Formation Converter — Demo Walkthrough

A self-contained, offline demo. No Ranger Admin, no AWS account, no credentials.

| File | What it is |
|---|---|
| `Demo_Ranger_Policies.json` | A synthetic Apache Ranger JSON export — 172 scanned policies (86 Hive, 56 Trino, 30 EMRFS; the tag service's policies are skipped) across four Ranger services. Shaped exactly like what the Ranger Admin "Export" button produces. |
| `demo-config.yaml` | Principal mapping (Ranger names → IAM ARNs) and the Trino GDC catalog name. |
| `generate_demo_policies.py` | Regenerates the export. Edit this, not the JSON. |
| `out/` | Assessment outputs (gitignored). |

## Setup

```bash
mvn clean package -DskipTests
python3 demo/generate_demo_policies.py   # writes Demo_Ranger_Policies.json
mkdir -p demo/out
```

`Demo_Ranger_Policies.json` is generated, not checked in — the repo ignores `*.json`.
The generator is deterministic (stable ids and guids), so everyone gets the byte-identical
export and the expected output below holds.

## The one command

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  file demo/Demo_Ranger_Policies.json \
  --config demo/demo-config.yaml \
  --output-lf-policies-path demo/out/lf_operations.json \
  --output-gaps-path demo/out/gaps.json \
  --console-only
```

Runs in a few seconds. Makes zero AWS calls.

## Expected output

```
=== Apache Ranger → Lake Formation Assessment ===
Source:       file:Demo_Ranger_Policies.json
Assessed at:  2026-09-18T18:02:03Z

Services assessed:
  acme_hive            (hive            ) — assessed  (86 policies)
  acme_trino           (trino           ) — assessed  (56 policies)
  acme_emrfs           (amazon-emr-emrfs) — assessed  (30 policies)
  acme_tags            (tag             ) — skipped: unsupported service type

Policies scanned:          172
  Fully convertible:       157 (91%)
  Partially convertible:     5 (3%)
  Not convertible:          10 (6%)
Projected LF grants:       201

Gaps detected (65 total):
  DATA_MASKING                :   1  — LF has no column masking. Consider removing or migrating to row-level filters.
  DENY_POLICY                 :   3  — Deny rules are emitted as Cedar forbid statements; review carefully before applying.
  DENY_EXCEPTION              :   1  — Deny exceptions are emitted as Cedar deny-exception permits; verify intended access.
  VALIDITY_SCHEDULE           :   1  — Time-bound access control is not supported in LF permissions.
  CUSTOM_CONDITION            :   1  — Attribute-based conditions cannot be expressed as LF permissions.
  SECURITY_ZONE               :   1  — Ranger Security Zones have no equivalent in Lake Formation.
  WILDCARD_PATTERN            :  14  — Wildcard patterns were not expanded (no AWS credentials); resources treated as-is.
  UNSUPPORTED_SERVICE_TYPE    :   1  — No adapter registered for this Ranger service type; policies will be skipped.
  UNSUPPORTED_ACTION          :   1  — One or more Ranger access types have no LF permission equivalent and will be dropped.
  KNOWN_UNMAPPABLE_ACTION     :   6  — Access types with no Lake Formation equivalent by design; dropped, and not counted against convertibility. See the rollup below.
  UNMAPPED_RESOURCE           :   2  — Resource paths cannot be mapped to LF data locations (HDFS/file:// not supported; see details below).
  SCHEMA_VALIDATION_FAILURE   :   1  — A Cedar statement failed schema validation and was excluded from conversion.
  CANNOT_VALIDATE_S3_LOCATION :  31  — No S3 Access Grants configuration provided. Cannot validate whether S3 locations are registered. Add s3AccessGrants config to enable validation.
  EXCLUDES_PATTERN            :   1  — isExcludes=true ("all except") patterns have no Lake Formation equivalent; these policies are skipped.

Unmapped resource paths (2) — convert to S3 URIs or ignore:
  [1025] hdfs://acme-nn01:8020/staging/inbound/
  [1025] hdfs://acme-nn01:8020/staging/inbound/

Known-unmappable access types (informational — no Lake Formation equivalent, no effect on convertibility):
  hive: repladmin          —       1 policy
  hive: serviceadmin       —       1 policy
  hive: index              —       1 policy
  hive: lock               —       1 policy
  trino: grant             —       1 policy
  trino: revoke            —       1 policy

Policies with gaps (20):
  ...  (see README.md's "Sample Console Output" for a representative excerpt of this section;
        full per-policy breakdown elided here for brevity)

LF permission operations written to: demo/out/lf_operations.json
Gap report written to: demo/out/gaps.json
```

If your numbers differ, the JSON was regenerated with different settings — re-run
`python3 demo/generate_demo_policies.py`.

### New in this run: two policies added for the `KNOWN_UNMAPPABLE_ACTION` gap

| Policy | Access types | Demonstrates |
|---|---|---|
| `curated-index-lock-maintenance` (Hive) | `select` + `index` + `lock` | A policy stays **fully convertible** despite carrying deliberately-unmapped access types — `index`/`lock` are reported as informational `KNOWN_UNMAPPABLE_ACTION` gaps and never demote it. |
| `raw-unrecognised-access-type` (Hive, id=1024) | `select` + `notarealprivilege` (a bogus type no adapter recognises) | A genuine coverage gap still demotes the policy — reported as the actionable `UNSUPPORTED_ACTION`, distinct from the informational type above. |

## Suggested narrative

### 1. Breadth — one tool, three engines, two authorization planes

Point at the "Services assessed" block. Hive, Trino, and EMRFS policies all enter the same
Cedar intermediate model. Hive and Trino become **Lake Formation** grants; EMRFS becomes
**S3 Access Grants** operations. The unsupported `tag` service is reported as skipped rather
than silently half-converted.

### 2. The clean conversions

Open `out/lf_operations.json` alongside the source policies:

| Policy | Shows |
|---|---|
| 1001 `curated-db-owner` | Database-level → LF `DATABASE` CREATE_TABLE/ALTER/DROP |
| 1002 `curated-daily-sales-read` | Table-level → LF `TABLE` SELECT |
| 1003 `customer360-nonpii-columns` | Column-level → LF `TABLE_WITH_COLUMNS`, PII columns never granted |
| 1004 `refined-full-control` | Ranger `all` → LF `ALL`, not expanded into a list |
| 1005 `refined-orders-multi-principal` | 6 principals × 2 access types in one policy → many grants. This is why grant counts exceed policy counts. |
| 1006 `refined-core-tables-read` | 5 tables in one policy → 5 grants |
| 1007 `steward-grantable-curated` | `delegateAdmin: true` → grant option set (`grantable: true` in the output) |
| 1014 `curated-s3-write-location` | Hive `url` on S3 → LF `DATA_LOCATION_ACCESS` |
| 1015 `decommissioned-legacy-grant` | `isEnabled: false` → dropped cleanly, no grant, no gap |

### 3. The centrepiece — Lake Formation has no deny, so Cedar does the subtraction

This is the part worth slowing down for.

- **1010 `clinical-analyst-read`** allows `data_analysts` SELECT on all three `clinical` tables.
- **1011 `clinical-deny-patient-pii`** is a separate deny policy on `clinical.patient_pii`.

Lake Formation is grant-only — it cannot express "allow these, deny that." The converter emits
Cedar `permit` + `forbid`, runs partial evaluation, and issues only the grants that survive:

```bash
python3 -c "
import json
for o in json.load(open('demo/out/lf_operations.json')):
    r = o['resource']
    if r.get('databaseName') == 'clinical':
        print(o['sourcePolicyId'], r.get('tableName'), o['permissions'], o['principalArn'].split('/')[-1])
"
```

```
hive:1010  trial_sites    ['SELECT']  AcmeDataAnalysts
hive:1010  trial_results  ['SELECT']  AcmeDataAnalysts
trino:2010 trial_sites    ['SELECT']  AcmeExternalPartners
hive:1020  trial_results  ['SELECT']  AcmeDataSteward
```

Three permits went in for `data_analysts`; two grants came out. **`patient_pii` is absent** — the
grant is never issued, rather than issued and then denied.

Then the cross-engine version: **2009** is a *Trino* deny on `clinical.trial_results` and
**2010** is a *Trino* allow on two tables for `external_partners`. Partners end up with
`trial_sites` only. Both engines' policies merge into one Cedar policy set before evaluation, so
a Trino deny cancels a permit regardless of which engine wrote it.

Also worth showing: **1012** (`allowExceptions` → Cedar forbid annotated `@allowException`) and
**1013** (`denyExceptions` → permit annotated `@denyException`).

### 4. Trino specifics

| Policy | Shows |
|---|---|
| 2001 `trino-curated-schema-browse` | Trino's `schema` key maps to a Glue database; `use`/`show` → LF `DESCRIBE`, which Hive's access types cannot express at all |
| 2003 `trino-refined-writeback` | Trino `delete` → LF `DELETE`, also unreachable from Hive |
| 2006 / 2007 | Policies on `postgresql_crm` and `mysql_legacy`. Because `gdcCatalogName: awsdatacatalog`, these are **filtered out** rather than turned into bogus LF grants for federated Postgres tables. Correct behaviour — but note the caveat in "Known rough edges" below. |

### 5. The honest part — the gap report

```
DATA_MASKING                :   1
DENY_POLICY                 :   3
DENY_EXCEPTION              :   1
VALIDITY_SCHEDULE           :   1
CUSTOM_CONDITION            :   1
SECURITY_ZONE               :   1
WILDCARD_PATTERN            :  14
UNSUPPORTED_SERVICE_TYPE    :   1
UNSUPPORTED_ACTION          :   1
KNOWN_UNMAPPABLE_ACTION     :   6
UNMAPPED_RESOURCE           :   2
SCHEMA_VALIDATION_FAILURE   :   1
CANNOT_VALIDATE_S3_LOCATION :  31
EXCLUDES_PATTERN            :   1
```

Every one of these is a deliberate policy in the file, and each maps to a concrete decision the
customer has to make:

| Policy | Gap | The decision it forces |
|---|---|---|
| 1016 `mask-employee-ssn` | `DATA_MASKING` | LF has no native masking. Redesign as a Glue view or drop the column from the grant. |
| 1017 `row-filter-us-orders-only` | row-level filter | The predicate cannot be enforced by a grant. See "Known rough edges". |
| 1018 `q4-audit-temporary-access` | `VALIDITY_SCHEDULE` | The grant converts; the expiry does not. Needs an external revocation job. |
| 1019 `conditional-ip-restricted-read` | `CUSTOM_CONDITION` | Source-IP restriction must move to an IAM condition or network control. |
| 1020 `zoned-clinical-trial-access` | `SECURITY_ZONE` | The grant converts, but the zone's isolation guarantee is lost. |
| 1021 `refined-all-tables-except-returns` | `EXCLUDES_PATTERN` | `isExcludes=true` has no LF equivalent. Rewrite as an explicit allow list. |
| `raw-unrecognised-access-type` (id=1024) | `UNSUPPORTED_ACTION` | Hive `notarealprivilege` — an access type no adapter recognises at all. A genuine coverage gap, so this one *does* demote the policy. |
| `hive-replication-admin` / `curated-index-lock-maintenance` / trino `grant`/`revoke` policy | `KNOWN_UNMAPPABLE_ACTION` | Hive `repladmin`/`serviceadmin`/`index`/`lock`, Trino `GRANT`/`REVOKE`. Engine operations Lake Formation deliberately never models — informational, does not demote convertibility. See the console's known-unmappable rollup and `knownUnmappableActions` in `gaps.json` for exact per-access-type counts. |
| 1023 `legacy-hdfs-staging-location` | `UNMAPPED_RESOURCE` | An `hdfs://` path. LF data locations are S3-only — a concrete migration task. |
| 4001 / 4002 | `UNSUPPORTED_SERVICE_TYPE` | Ranger tag-based policies. LF-Tags are the eventual target, not wired up yet. |

The two large counts are both artifacts of running offline, not problems with the policies:

- **`WILDCARD_PATTERN` (14)** — glob patterns like `campaign_*` and bare `*` could not be
  expanded because there is no Glue catalog to expand them against. The ARNs are placeholders.
  Add `--aws-region us-east-1 --aws-profile <profile>` to expand them for real.
- **`CANNOT_VALIDATE_S3_LOCATION` (31)** — one per EMRFS S3 prefix. Without an
  `s3AccessGrants` config the tool cannot check whether the prefixes are registered
  locations. Informational.

Note `KNOWN_UNMAPPABLE_ACTION` (6) is also informational — it never demotes a policy, unlike
`UNSUPPORTED_ACTION` above it. It counts *distinct access types* in the collapsed `gaps.json`
report, not raw occurrences; see the README's Gap Types / JSON Report Format sections.

### 6. Optional closer — the same file at scale

`Dev_Ranger_Hive_Policies.json`, `Dev_Ranger_Trino_Policies.json` and
`Dev_Ranger_EMRFS_Policies.json` in the repository root are real customer exports. Same command,
no config file needed. Useful for "and here it is against production policies."

## Known rough edges

Be ready for these; better to name them than be caught by them.

1. **Deny-only policies count as "not convertible."** Policies 1011 and 2009 exist purely to
   subtract. They produce no grants of their own, so the report bins them with the failures. The
   `DENY_POLICY` gap entry explains it, but the headline number slightly overstates the problem.

2. **Catalog-filtered policies produce no gap entry.** 2006 and 2007 are filtered out correctly,
   but they appear in the "not convertible" count with *nothing* in the gap report to explain
   why. If someone asks "what happened to those two?", the report cannot answer — you have to.

3. **The row-filter policy (1017) reports `SCHEMA_VALIDATION_FAILURE`, not a row-filter gap.**
   The converter emits a Cedar statement carrying the filter expression, and its own schema
   rejects it. The end result is right — no grants are issued, so no unfiltered data is exposed —
   but the reported reason is a tool defect rather than a clean "LF has no row filtering" gap.

4. **Wildcards need Glue.** Offline, no wildcard policy produces a usable grant. If the audience
   cares about wildcard expansion, run that segment with `--aws-region` / `--aws-profile` against
   a catalog that actually contains the demo databases.

5. **Grant attribution is lost when operations merge.** If two policies produce the same
   (principal, resource, permission) grant, they merge into one operation that keeps only one
   policy's ID — so the other policy can look "not convertible" despite converting fine. The
   filler policies in the generator deliberately avoid overlapping principals to keep this out of
   the demo; real customer exports will hit it.

## Editing the demo

Edit `generate_demo_policies.py` and re-run it. Two rules the generator documents inline and that
are easy to get wrong:

- A **database-level** policy must carry *only* the `database` resource key. Adding
  `table: *` / `column: *` pushes the converter to column level, where `CREATE_TABLE`/`ALTER`/
  `DROP` are not valid LF permissions and are silently dropped — yielding zero grants.
- Filler policies must not touch resources a showcase policy tells a story about
  (`FILLER_EXCLUDED_DBS` / `FILLER_EXCLUDED_TABLES`). Otherwise a filler policy cheerfully grants
  `external_partners` access to `clinical.patient_pii` right after you claimed it was protected.
