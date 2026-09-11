# Ranger → Lake Formation Converter — Demo Walkthrough

A self-contained, offline demo. No Ranger Admin, no AWS account, no credentials.

| File | What it is |
|---|---|
| `Demo_Ranger_Policies.json` | A synthetic Apache Ranger JSON export — 173 policies across four Ranger services. Shaped exactly like what the Ranger Admin "Export" button produces. |
| `demo-config.yaml` | Principal mapping (Ranger names → IAM ARNs) and the Trino GDC catalog name. |
| `generate_demo_policies.py` | Regenerates the export. Edit this, not the JSON. |
| `out/` | Assessment outputs (gitignored). |

## Setup

```bash
mvn clean package -DskipTests
mkdir -p demo/out
```

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
Services assessed:
  acme_hive            (hive            ) — assessed  (84 policies)
  acme_trino           (trino           ) — assessed  (56 policies)
  acme_emrfs           (amazon-emr-emrfs) — assessed  (30 policies)
  acme_tags            (tag             ) — skipped: unsupported service type

Policies scanned:          170
  Fully convertible:       156 (92%)
  Partially convertible:     4 (2%)
  Not convertible:          10 (6%)
Projected LF grants:       199
```

If your numbers differ, the JSON was regenerated with different settings — re-run
`python3 demo/generate_demo_policies.py`.

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
UNSUPPORTED_ACTION          :   4
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
| 1022 / 2008 | `UNSUPPORTED_ACTION` | Hive `repladmin`/`serviceadmin`, Trino `GRANT`/`REVOKE`. Engine operations, not data permissions. |
| 1023 `legacy-hdfs-staging-location` | `UNMAPPED_RESOURCE` | An `hdfs://` path. LF data locations are S3-only — a concrete migration task. |
| 4001 / 4002 | `UNSUPPORTED_SERVICE_TYPE` | Ranger tag-based policies. LF-Tags are the eventual target, not wired up yet. |

The two large counts are both artifacts of running offline, not problems with the policies:

- **`WILDCARD_PATTERN` (14)** — glob patterns like `campaign_*` and bare `*` could not be
  expanded because there is no Glue catalog to expand them against. The ARNs are placeholders.
  Add `--aws-region us-east-1 --aws-profile <profile>` to expand them for real.
- **`CANNOT_VALIDATE_S3_LOCATION` (31)** — one per EMRFS S3 prefix. Without an
  `s3AccessGrants` config the tool cannot check whether the prefixes are registered
  locations. Informational.

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
