#!/usr/bin/env python3
"""Generate Demo_Ranger_Policies.json — a curated Apache Ranger export used to
demonstrate the Ranger -> Lake Formation converter.

The file is shaped exactly like a real Ranger Admin JSON export (the payload the
"Export" button produces), so it can be fed straight to the assessment tool:

    java -jar target/assessment-jar-with-dependencies.jar \
        file demo/Demo_Ranger_Policies.json \
        --config demo/demo-config.yaml \
        --output-lf-policies-path demo/out/lf_operations.json \
        --output-gaps-path demo/out/gaps.json \
        --console-only

Every policy is deliberate. Showcase policies carry a `policyLabels` entry of the
form `demo:<capability>` naming what they prove, and a `description` written for a
human reading over your shoulder. Filler policies fill the file out to a realistic
size so the console summary shows meaningful counts.

Regenerate with:  python3 demo/generate_demo_policies.py
"""

import json
import uuid
from pathlib import Path

OUT_PATH = Path(__file__).parent / "Demo_Ranger_Policies.json"

# --------------------------------------------------------------------------------------
# Scenario: AcmeCorp's analytics data lake, mid-migration from EMR/Hive+Trino to
# Lake Formation. Medallion-style raw/refined/curated layers plus domain databases.
# --------------------------------------------------------------------------------------

SVC_HIVE = "acme_hive"
SVC_TRINO = "acme_trino"
SVC_EMRFS = "acme_emrfs"
SVC_TAGS = "acme_tags"

GDC_CATALOG = "awsdatacatalog"  # must match gdcCatalogName in demo-config.yaml

# Principals. Every name here is mapped to an IAM ARN in demo-config.yaml so the
# projected grants read as real ARNs instead of "ranger-user:alice_analyst".
U_ANALYST = "alice_analyst"
U_ENGINEER = "bob_engineer"
U_DPO = "carol_dpo"
U_CONTRACTOR = "dave_contractor"
U_STEWARD = "erin_steward"

G_ANALYSTS = "data_analysts"
G_ENGINEERS = "data_engineers"
G_FINANCE = "finance_readers"
G_HR = "hr_admins"
G_MARKETING = "marketing_bi"
G_PARTNERS = "external_partners"

R_STEWARD = "lf_data_steward"

# Databases and their tables.
LAKE = {
    "acme_raw": ["orders_raw", "customers_raw", "clickstream_raw", "shipments_raw",
                 "inventory_raw", "payments_raw", "returns_raw", "suppliers_raw"],
    "acme_refined": ["orders", "customers", "products", "shipments", "returns",
                     "inventory", "payments", "suppliers", "price_history"],
    "acme_curated": ["daily_sales", "customer_360", "product_margin", "churn_scores",
                     "cohort_retention", "supplier_scorecard", "inventory_health"],
    "finance": ["gl_entries", "invoices", "revenue_recognition", "cost_centers",
                "budget_actuals", "fx_rates", "tax_provisions"],
    "hr": ["employees", "compensation", "headcount_plan", "org_hierarchy", "training_records"],
    "marketing": ["campaigns", "attribution", "audience_segments", "spend_by_channel",
                  "email_engagement", "web_sessions"],
    "clinical": ["trial_sites", "trial_results", "patient_pii"],
}

# ID ranges are kept disjoint per service so a policy ID in the report immediately
# tells you which Ranger service it came from.
ID_BASE = {SVC_HIVE: 1000, SVC_TRINO: 2000, SVC_EMRFS: 3000, SVC_TAGS: 4000}
_next_id = dict(ID_BASE)

SERVICE_TYPE = {
    SVC_HIVE: "hive",
    SVC_TRINO: "trino",
    SVC_EMRFS: "amazon-emr-emrfs",
    SVC_TAGS: "tag",
}


def _alloc_id(service):
    _next_id[service] += 1
    return _next_id[service]


def res(values, excludes=False, recursive=False):
    """A Ranger resource entry."""
    return {
        "values": values if isinstance(values, list) else [values],
        "isExcludes": excludes,
        "isRecursive": recursive,
    }


def item(accesses, users=None, groups=None, roles=None,
         delegate_admin=False, conditions=None):
    """A Ranger policy item (allow, deny, allowException or denyException)."""
    return {
        "accesses": [{"type": a, "isAllowed": True} for a in accesses],
        "users": users or [],
        "groups": groups or [],
        "roles": roles or [],
        "conditions": conditions or [],
        "delegateAdmin": delegate_admin,
    }


def policy(service, name, resources, allow=None, description="", labels=None,
           deny=None, allow_exceptions=None, deny_exceptions=None,
           policy_type=0, data_mask=None, row_filter=None,
           validity=None, conditions=None, zone="", enabled=True,
           deny_all_else=False, priority=0):
    """Assemble one Ranger policy in export format."""
    pid = _alloc_id(service)
    return {
        "service": service,
        "name": name,
        "policyType": policy_type,
        "policyPriority": priority,
        "description": description,
        "isAuditEnabled": True,
        "resources": resources,
        "policyItems": allow or [],
        "denyPolicyItems": deny or [],
        "allowExceptions": allow_exceptions or [],
        "denyExceptions": deny_exceptions or [],
        "dataMaskPolicyItems": data_mask or [],
        "rowFilterPolicyItems": row_filter or [],
        "serviceType": SERVICE_TYPE[service],
        "options": {},
        "validitySchedules": validity or [],
        "policyLabels": labels or [],
        "conditions": conditions or [],
        "zoneName": zone,
        "isDenyAllElse": deny_all_else,
        "id": pid,
        "guid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"acme/{service}/{name}")),
        "isEnabled": enabled,
        "version": 1,
    }


# ======================================================================================
# Hive showcase policies
#
# Reminder on Hive access-type mapping (HiveServiceAdapter):
#   select -> SELECT   update -> INSERT   create -> CREATE_TABLE
#   drop   -> DROP     alter  -> ALTER    all    -> ALL
#   read   -> SELECT   write  -> INSERT
# and each Cedar action is then filtered by resource level, so database-level policies
# must use create/alter/drop/all (SELECT is not a valid database-level LF permission).
# ======================================================================================

def hive_showcase():
    p = []

    # Note: a database-level policy must carry ONLY the `database` resource key. Adding
    # table:* / column:* pushes the converter to column level, where CREATE_TABLE/ALTER/DROP
    # are not valid LF permissions and are silently filtered out — yielding zero grants.
    p.append(policy(
        SVC_HIVE, "curated-db-owner",
        {"database": res("acme_curated")},
        allow=[item(["create", "alter", "drop"], groups=[G_ENGINEERS])],
        description="Database-level ownership. Converts to LF DATABASE permissions "
                    "CREATE_TABLE, ALTER, DROP on acme_curated.",
        labels=["demo:database-level"]))

    p.append(policy(
        SVC_HIVE, "curated-daily-sales-read",
        {"database": res("acme_curated"), "table": res("daily_sales"), "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS])],
        description="Plain table read. Converts to a single LF TABLE SELECT grant.",
        labels=["demo:table-level"]))

    p.append(policy(
        SVC_HIVE, "customer360-nonpii-columns",
        {"database": res("acme_curated"), "table": res("customer_360"),
         "column": res(["customer_id", "region", "segment", "lifetime_value"])},
        allow=[item(["select"], groups=[G_MARKETING])],
        description="Column-level read. Converts to an LF TABLE_WITH_COLUMNS grant "
                    "listing only the four permitted columns — the PII columns on the "
                    "same table are never granted.",
        labels=["demo:column-level"]))

    p.append(policy(
        SVC_HIVE, "refined-full-control",
        {"database": res("acme_refined"), "table": res("*"), "column": res("*")},
        allow=[item(["all"], groups=[G_ENGINEERS])],
        description="Ranger 'all' access type. Converts to the LF ALL permission "
                    "rather than being expanded into every individual permission.",
        labels=["demo:all-access"]))

    p.append(policy(
        SVC_HIVE, "refined-orders-multi-principal",
        {"database": res("acme_refined"), "table": res("orders"), "column": res("*")},
        allow=[item(["select", "update"],
                    users=[U_ANALYST, U_ENGINEER, U_STEWARD],
                    groups=[G_ANALYSTS, G_ENGINEERS],
                    roles=[R_STEWARD])],
        description="One Ranger policy, six principals, two access types. Fans out to "
                    "12 LF grants — this is why projected grant counts exceed policy counts.",
        labels=["demo:principal-fanout"]))

    p.append(policy(
        SVC_HIVE, "refined-core-tables-read",
        {"database": res("acme_refined"),
         "table": res(["orders", "customers", "products", "shipments", "returns"]),
         "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS])],
        description="Five tables in one policy. Fans out to five separate LF TABLE grants.",
        labels=["demo:resource-fanout"]))

    p.append(policy(
        SVC_HIVE, "steward-grantable-curated",
        {"database": res("acme_curated"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], roles=[R_STEWARD], delegate_admin=True)],
        description="delegateAdmin=true. Converts to an LF grant with the grant option "
                    "set (permissionsWithGrantOption), letting the steward re-grant.",
        labels=["demo:grantable"]))

    p.append(policy(
        SVC_HIVE, "marketing-campaign-tables-wildcard",
        {"database": res("marketing"), "table": res("campaign_*"), "column": res("*")},
        allow=[item(["select"], groups=[G_MARKETING])],
        description="Glob table pattern. With AWS credentials the converter expands "
                    "'campaign_*' against the Glue catalog; offline it is reported as a "
                    "WILDCARD_PATTERN so you know the ARN is a placeholder.",
        labels=["demo:wildcard-glob"]))

    p.append(policy(
        SVC_HIVE, "analysts-read-all-raw",
        {"database": res("acme_raw"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS])],
        description="Bare '*' table. Uses Lake Formation's native TableWildcard rather "
                    "than enumerating tables, so new tables are covered automatically.",
        labels=["demo:wildcard-all-tables"]))

    # ---- The Cedar story: permit minus forbid -----------------------------------------
    # Modelled the way Ranger operators actually do it: a broad allow policy, and a separate,
    # narrower deny policy. Ranger deny items only apply to their own policy's resources, so
    # carving out one table genuinely requires two policies.
    #
    # The tables are listed explicitly rather than as clinical.* on purpose: offline there is no
    # Glue catalog, so a wildcard stays an unexpanded placeholder and would never intersect the
    # concrete forbid — the subtraction would be invisible in the demo.
    p.append(policy(
        SVC_HIVE, "clinical-analyst-read",
        {"database": res("clinical"),
         "table": res(["trial_sites", "trial_results", "patient_pii"]),
         "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS])],
        description="Broad allow across all three clinical tables. Read together with "
                    "clinical-deny-patient-pii below: three permits go in, and after Cedar "
                    "partial evaluation only two LF grants come out.",
        labels=["demo:deny-forbid", "demo:cedar-partial-eval"]))

    p.append(policy(
        SVC_HIVE, "clinical-deny-patient-pii",
        {"database": res("clinical"), "table": res("patient_pii"), "column": res("*")},
        deny=[item(["select"], groups=[G_ANALYSTS])],
        description="The carve-out. Lake Formation has no deny model at all, so the converter "
                    "emits a Cedar forbid and resolves permit-minus-forbid itself — the "
                    "patient_pii grant is simply never issued. Flagged DENY_POLICY for review, "
                    "because this is the semantic that most often surprises people migrating "
                    "off Ranger.",
        labels=["demo:deny-forbid", "demo:cedar-partial-eval"]))

    p.append(policy(
        SVC_HIVE, "hr-read-except-contractors",
        {"database": res("hr"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=[G_HR])],
        allow_exceptions=[item(["select"], users=[U_CONTRACTOR])],
        description="allowExceptions: everyone in hr_admins may read HR data except the "
                    "contractor. Becomes a Cedar forbid annotated @allowException, so the "
                    "contractor's grant is withheld while the group grant stands.",
        labels=["demo:allow-exception"]))

    p.append(policy(
        SVC_HIVE, "partners-blocked-except-dpo",
        {"database": res("finance"), "table": res("invoices"), "column": res("*")},
        allow=[item(["select"], groups=[G_FINANCE])],
        deny=[item(["select"], groups=[G_PARTNERS])],
        deny_exceptions=[item(["select"], users=[U_DPO])],
        description="denyExceptions: partners are denied, but the DPO is carved back out "
                    "of that deny. Becomes a Cedar permit annotated @denyException.",
        labels=["demo:deny-exception"]))

    # ---- Data location ---------------------------------------------------------------
    p.append(policy(
        SVC_HIVE, "curated-s3-write-location",
        {"url": res("s3://acme-datalake-curated/warehouse/", recursive=True)},
        allow=[item(["write", "read"], groups=[G_ENGINEERS])],
        description="Hive 'url' resource on an S3 path. Converts to an LF "
                    "DATA_LOCATION_ACCESS grant, which is what lets engineers register "
                    "and write new tables under that prefix.",
        labels=["demo:data-location"]))

    p.append(policy(
        SVC_HIVE, "decommissioned-legacy-grant",
        {"database": res("acme_raw"), "table": res("legacy_dump"), "column": res("*")},
        allow=[item(["select"], users=[U_CONTRACTOR])],
        enabled=False,
        description="isEnabled=false. Disabled policies are dropped before conversion, so "
                    "no LF grant is projected and no gap is raised.",
        labels=["demo:disabled-policy"]))

    # ---- Curated gaps: one clear example of each ------------------------------------
    p.append(policy(
        SVC_HIVE, "mask-employee-ssn",
        {"database": res("hr"), "table": res("employees"), "column": res("ssn")},
        policy_type=1,
        data_mask=[{
            "accesses": [{"type": "select", "isAllowed": True}],
            "users": [], "groups": [G_ANALYSTS], "roles": [],
            "conditions": [], "delegateAdmin": False,
            "dataMaskInfo": {"dataMaskType": "MASK_SHOW_LAST_4"},
        }],
        description="GAP (DATA_MASKING): Ranger column masking. Lake Formation has no "
                    "native masking, so this policy produces no grants and must be "
                    "redesigned — typically as a Glue view or column exclusion.",
        labels=["gap:data-masking"]))

    p.append(policy(
        SVC_HIVE, "row-filter-us-orders-only",
        {"database": res("acme_refined"), "table": res("orders")},
        policy_type=2,
        row_filter=[{
            "accesses": [{"type": "select", "isAllowed": True}],
            "users": [], "groups": [G_PARTNERS], "roles": [],
            "conditions": [], "delegateAdmin": False,
            "rowFilterInfo": {"filterExpr": "country = 'US'"},
        }],
        description="GAP (row-level security): Ranger row filter. Lake Formation row-level "
                    "filtering is not available through this path, so the filter predicate "
                    "cannot be enforced by the projected grants.",
        labels=["gap:row-filter"]))

    p.append(policy(
        SVC_HIVE, "q4-audit-temporary-access",
        {"database": res("finance"), "table": res("gl_entries"), "column": res("*")},
        allow=[item(["select"], users=[U_DPO])],
        validity=[{
            "startTime": "2026/10/01 00:00:00",
            "endTime": "2026/12/31 23:59:59",
            "timeZone": "America/New_York",
        }],
        description="GAP (VALIDITY_SCHEDULE): time-boxed access. The grant converts, but "
                    "the expiry does not — Lake Formation grants have no end date, so this "
                    "needs an external process to revoke it.",
        labels=["gap:validity-schedule"]))

    p.append(policy(
        SVC_HIVE, "conditional-ip-restricted-read",
        {"database": res("finance"), "table": res("revenue_recognition"), "column": res("*")},
        allow=[item(["select"], groups=[G_FINANCE],
                    conditions=[{"type": "accessFromClientIPAddress",
                                 "values": ["10.20.0.0/16"]}])],
        description="GAP (CUSTOM_CONDITION): source-IP restriction on the policy item. The "
                    "grant converts but the IP condition is dropped — it has to move to an "
                    "IAM policy condition or network control.",
        labels=["gap:custom-condition"]))

    p.append(policy(
        SVC_HIVE, "zoned-clinical-trial-access",
        {"database": res("clinical"), "table": res("trial_results"), "column": res("*")},
        allow=[item(["select"], users=[U_STEWARD])],
        zone="clinical_restricted",
        description="GAP (SECURITY_ZONE): policy lives in a Ranger security zone. Lake "
                    "Formation has no zone concept, so the zone's isolation guarantee is "
                    "lost even though the grant itself converts.",
        labels=["gap:security-zone"]))

    p.append(policy(
        SVC_HIVE, "refined-all-tables-except-returns",
        {"database": res("acme_refined"),
         "table": res(["returns", "customers"], excludes=True),
         "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS])],
        description="GAP (EXCLUDES_PATTERN): isExcludes=true means 'every table except "
                    "these'. Lake Formation has no 'all except' model, so the policy is not "
                    "convertible and must be rewritten as an explicit allow list.",
        labels=["gap:excludes-pattern"]))

    p.append(policy(
        SVC_HIVE, "hive-replication-admin",
        {"database": res("acme_raw"), "table": res("*"), "column": res("*")},
        allow=[item(["repladmin", "serviceadmin"], users=[U_ENGINEER])],
        description="GAP (UNSUPPORTED_ACTION): Hive replication and service admin rights "
                    "have no Lake Formation equivalent. These are engine operations, not "
                    "data permissions, so nothing is projected.",
        labels=["gap:unsupported-action"]))

    p.append(policy(
        SVC_HIVE, "legacy-hdfs-staging-location",
        {"url": res("hdfs://acme-nn01:8020/staging/inbound/", recursive=True)},
        allow=[item(["write"], groups=[G_ENGINEERS])],
        description="GAP (UNMAPPED_RESOURCE): an HDFS path. Lake Formation data locations "
                    "are S3-only, so this must be migrated to an s3:// prefix before it can "
                    "convert. A concrete, actionable migration task.",
        labels=["gap:unmapped-resource"]))

    return p


# ======================================================================================
# Trino showcase policies
#
# TrinoServiceAdapter mapping:
#   select -> SELECT   insert -> INSERT   delete -> DELETE   create -> CREATE_TABLE
#   drop   -> DROP     alter  -> ALTER    use/show -> DESCRIBE
# Trino's resource hierarchy is catalog -> schema -> table -> column, and "schema" is
# the Glue database.
# ======================================================================================

def trino_showcase():
    p = []

    p.append(policy(
        SVC_TRINO, "trino-curated-schema-browse",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_curated")},
        allow=[item(["use", "show"], groups=[G_ANALYSTS])],
        description="Trino's 'schema' resource key maps to a Glue database. use/show "
                    "convert to LF DESCRIBE — a permission Hive's access types cannot "
                    "express at all.",
        labels=["demo:trino-schema-key", "demo:describe"]))

    p.append(policy(
        SVC_TRINO, "trino-daily-sales-read",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_curated"),
         "table": res("daily_sales"), "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS, G_MARKETING])],
        description="Trino table read under the Glue catalog. Converts identically to the "
                    "equivalent Hive policy — one intermediate Cedar model, many engines.",
        labels=["demo:trino-table-level"]))

    p.append(policy(
        SVC_TRINO, "trino-refined-writeback",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_refined"),
         "table": res("returns"), "column": res("*")},
        allow=[item(["select", "insert", "delete"], groups=[G_ENGINEERS])],
        description="Trino 'delete' converts to the LF DELETE permission. Hive's access "
                    "types have no DELETE, so this is a capability only reachable through "
                    "the Trino service definition.",
        labels=["demo:trino-delete"]))

    p.append(policy(
        SVC_TRINO, "trino-attribution-columns",
        {"catalog": res(GDC_CATALOG), "schema": res("marketing"),
         "table": res("attribution"),
         "column": res(["campaign_id", "channel", "conversions", "spend_usd"])},
        allow=[item(["select"], groups=[G_MARKETING])],
        description="Trino column-level read -> LF TABLE_WITH_COLUMNS.",
        labels=["demo:trino-column-level"]))

    p.append(policy(
        SVC_TRINO, "trino-schema-owner-refined",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_refined")},
        allow=[item(["create", "alter", "drop"], groups=[G_ENGINEERS], delegate_admin=True)],
        description="Schema-level ownership with delegateAdmin -> grantable LF DATABASE "
                    "permissions.",
        labels=["demo:trino-schema-owner", "demo:grantable"]))

    p.append(policy(
        SVC_TRINO, "trino-postgres-crm-read",
        {"catalog": res("postgresql_crm"), "schema": res("public"),
         "table": res("accounts"), "column": res("*")},
        allow=[item(["select"], groups=[G_ANALYSTS])],
        description="A Trino policy on a NON-Glue catalog (postgresql_crm). Because "
                    "gdcCatalogName is 'awsdatacatalog', the converter filters this policy "
                    "out rather than inventing a Lake Formation grant for a federated "
                    "Postgres table. Correct behaviour, not a gap.",
        labels=["demo:catalog-filtering"]))

    p.append(policy(
        SVC_TRINO, "trino-mysql-legacy-read",
        {"catalog": res("mysql_legacy"), "schema": res("billing"),
         "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=[G_FINANCE])],
        description="Second non-Glue catalog, also filtered out. Real Trino deployments "
                    "front many catalogs; only the Glue one is Lake Formation's business.",
        labels=["demo:catalog-filtering"]))

    p.append(policy(
        SVC_TRINO, "trino-grant-management",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_curated"),
         "table": res("*"), "column": res("*")},
        allow=[item(["grant", "revoke"], roles=[R_STEWARD])],
        description="GAP (UNSUPPORTED_ACTION): Trino GRANT/REVOKE privileges. Lake "
                    "Formation models re-granting as the grant option on a permission, not "
                    "as standalone privileges, so these are dropped.",
        labels=["gap:unsupported-action"]))

    p.append(policy(
        SVC_TRINO, "trino-clinical-deny-partners",
        {"catalog": res(GDC_CATALOG), "schema": res("clinical"),
         "table": res("trial_results"), "column": res("*")},
        deny=[item(["select"], groups=[G_PARTNERS])],
        description="A Trino deny policy. It suppresses the partner grant that the Hive-side "
                    "allow would otherwise produce — the Cedar layer merges both engines' "
                    "policies into one policy set before evaluating, so a Trino deny really "
                    "does cancel a Hive permit.",
        labels=["demo:deny-forbid", "demo:cross-engine"]))

    p.append(policy(
        SVC_TRINO, "trino-clinical-partner-read",
        {"catalog": res(GDC_CATALOG), "schema": res("clinical"),
         "table": res(["trial_sites", "trial_results"]), "column": res("*")},
        allow=[item(["select"], groups=[G_PARTNERS])],
        description="The partner allow that the deny above partially cancels: two permits in, "
                    "one LF grant out.",
        labels=["demo:deny-forbid"]))

    p.append(policy(
        SVC_TRINO, "trino-raw-clickstream-wildcard",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_raw"),
         "table": res("clickstream_*"), "column": res("*")},
        allow=[item(["select"], groups=[G_ENGINEERS])],
        description="Glob pattern on the Trino side; same wildcard expansion path as Hive.",
        labels=["demo:wildcard-glob"]))

    return p


# ======================================================================================
# EMRFS showcase policies
#
# EmrfsServiceAdapter: resource key is `sthreeresource`, access types are case-sensitive
# GetObject / PutObject / ListObjects / DeleteObject, and output is S3 Access Grants
# operations — NOT Lake Formation grants.
# ======================================================================================

def emrfs_showcase():
    p = []

    p.append(policy(
        SVC_EMRFS, "emrfs-curated-read",
        {"sthreeresource": res("acme-datalake-curated/warehouse", recursive=True)},
        allow=[item(["GetObject", "ListObjects"], groups=[G_ANALYSTS])],
        description="EMRFS S3 prefix access. This does NOT become a Lake Formation grant — "
                    "it becomes an S3 Access Grants operation. The tool targets two AWS "
                    "authorization planes from the same Ranger source.",
        labels=["demo:emrfs-s3-access-grants"]))

    p.append(policy(
        SVC_EMRFS, "emrfs-raw-ingest-write",
        {"sthreeresource": res("acme-datalake-raw/inbound", recursive=True)},
        allow=[item(["GetObject", "PutObject", "ListObjects", "DeleteObject"],
                    groups=[G_ENGINEERS])],
        description="Read-write EMRFS prefix for the ingest job. All four S3 actions map "
                    "across.",
        labels=["demo:emrfs-readwrite"]))

    p.append(policy(
        SVC_EMRFS, "emrfs-scratch-space",
        {"sthreeresource": res("acme-emr-scratch/tmp", recursive=True)},
        allow=[item(["GetObject", "PutObject", "DeleteObject"],
                    users=[U_ENGINEER, U_ANALYST])],
        description="Per-user scratch space. Note the per-(principal x prefix) fan-out that "
                    "drives S3 Access Grants counts.",
        labels=["demo:emrfs-scratch"]))

    p.append(policy(
        SVC_EMRFS, "emrfs-partner-dropbox-readonly",
        {"sthreeresource": res("acme-partner-exchange/outbound", recursive=True)},
        allow=[item(["GetObject", "ListObjects"], groups=[G_PARTNERS])],
        description="External partner read-only prefix — the S3-side counterpart to the "
                    "Lake Formation partner restrictions.",
        labels=["demo:emrfs-partner"]))

    return p


# ======================================================================================
# Tag service — demonstrates how an unsupported Ranger service type is handled
# ======================================================================================

def tag_policies():
    return [
        policy(
            SVC_TAGS, "tag-pii-deny-analysts",
            {"tag": res("PII")},
            allow=[item(["hive:select"], groups=[G_ANALYSTS])],
            description="GAP (UNSUPPORTED_SERVICE_TYPE): Ranger tag-based policy. There is "
                        "no adapter for the 'tag' service type, so the whole service is "
                        "reported as skipped rather than half-converted. LF-Tags are the "
                        "eventual target but are not wired up yet.",
            labels=["gap:tag-based"]),
        policy(
            SVC_TAGS, "tag-confidential-restrict",
            {"tag": res("CONFIDENTIAL")},
            allow=[item(["hive:select"], roles=[R_STEWARD])],
            description="GAP (UNSUPPORTED_SERVICE_TYPE): second tag policy, so the report "
                        "shows a service-level skip count rather than a single stray policy.",
            labels=["gap:tag-based"]),
    ]


# ======================================================================================
# Filler — realistic, fully convertible policies that pad the export to a credible size.
# These are intentionally boring: they should all land in the "fully convertible" bucket.
# ======================================================================================

# G_ENGINEERS is deliberately absent: it is the writer group below. If a reader policy and a
# writer policy on the same table both name the same principal, the two grants merge into one
# LF operation and only one policy keeps the attribution — making the other look "not
# convertible" in the report even though it converted fine.
READER_GROUPS = [G_ANALYSTS, G_MARKETING, G_FINANCE, G_HR]
READER_USERS = [U_ANALYST, U_STEWARD, U_DPO]

# Filler must not touch resources the showcase policies tell a story about. Without this, a
# filler policy happily grants external_partners access to clinical.patient_pii moments after
# the deny-carve-out slide claimed it was protected — and filler grants that collide with a
# showcase deny get cancelled, showing up as unexplained "not convertible" rows.
FILLER_EXCLUDED_DBS = {"clinical"}
FILLER_EXCLUDED_TABLES = {
    ("finance", "invoices"),        # partners-blocked-except-dpo
    ("finance", "gl_entries"),      # q4-audit-temporary-access
    ("finance", "revenue_recognition"),  # conditional-ip-restricted-read
    ("hr", "employees"),            # mask-employee-ssn
    ("acme_refined", "orders"),     # row-filter-us-orders-only
    ("acme_refined", "returns"),    # refined-all-tables-except-returns / trino-refined-writeback
    ("acme_curated", "daily_sales"),
    ("acme_curated", "customer_360"),
    ("marketing", "attribution"),
}


def _filler_skip(db, table):
    return db in FILLER_EXCLUDED_DBS or (db, table) in FILLER_EXCLUDED_TABLES


def hive_filler(target_count):
    """Per-team table and column grants across the lake."""
    out = []
    i = 0
    for db, tables in LAKE.items():
        for table in tables:
            if len(out) >= target_count:
                return out
            if _filler_skip(db, table):
                continue
            grp = READER_GROUPS[i % len(READER_GROUPS)]
            usr = READER_USERS[i % len(READER_USERS)]
            i += 1

            out.append(policy(
                SVC_HIVE, f"hive-read-{db}-{table}",
                {"database": res(db), "table": res(table), "column": res("*")},
                allow=[item(["select"], groups=[grp], users=[usr])],
                description=f"Team read access to {db}.{table}.",
                labels=["filler:table-read"]))

            if len(out) >= target_count:
                return out

            # Every third table also gets a narrower column grant and a writer grant,
            # so the projected-grant mix is not uniformly one-permission-per-policy.
            if i % 3 == 0:
                out.append(policy(
                    SVC_HIVE, f"hive-cols-{db}-{table}",
                    {"database": res(db), "table": res(table),
                     "column": res(["id", "created_at", "updated_at"])},
                    allow=[item(["select"], groups=[G_PARTNERS])],
                    description=f"Restricted column set on {db}.{table} for external partners.",
                    labels=["filler:column-read"]))
            elif i % 3 == 1:
                out.append(policy(
                    SVC_HIVE, f"hive-write-{db}-{table}",
                    {"database": res(db), "table": res(table), "column": res("*")},
                    allow=[item(["select", "update", "alter"], groups=[G_ENGINEERS])],
                    description=f"Pipeline write access to {db}.{table}.",
                    labels=["filler:table-write"]))
            else:
                out.append(policy(
                    SVC_HIVE, f"hive-db-describe-{db}-{table}",
                    {"database": res(db), "table": res(table), "column": res("*")},
                    allow=[item(["select"], roles=[R_STEWARD], delegate_admin=True)],
                    description=f"Steward grantable read on {db}.{table}.",
                    labels=["filler:grantable-read"]))
    return out


def trino_filler(target_count):
    """Trino equivalents, plus a few policies on non-Glue catalogs that get filtered."""
    out = []
    i = 0
    for db, tables in LAKE.items():
        for table in tables:
            if len(out) >= target_count:
                return out
            if _filler_skip(db, table):
                continue
            grp = READER_GROUPS[i % len(READER_GROUPS)]
            i += 1
            out.append(policy(
                SVC_TRINO, f"trino-read-{db}-{table}",
                {"catalog": res(GDC_CATALOG), "schema": res(db),
                 "table": res(table), "column": res("*")},
                allow=[item(["select", "use"], groups=[grp])],
                description=f"Trino read access to {GDC_CATALOG}.{db}.{table}.",
                labels=["filler:trino-table-read"]))

            if len(out) >= target_count:
                return out
            # Only the two explicit showcase policies sit on non-Glue catalogs. Catalog-filtered
            # policies produce no operations AND no gap entry, so they land in the report's
            # "not convertible" bucket with nothing to explain them. Two are a talking point;
            # a dozen would just look like a dozen failures.
            out.append(policy(
                SVC_TRINO, f"trino-write-{db}-{table}",
                {"catalog": res(GDC_CATALOG), "schema": res(db),
                 "table": res(table), "column": res("*")},
                allow=[item(["select", "insert"], groups=[G_ENGINEERS])],
                description=f"Trino write access to {GDC_CATALOG}.{db}.{table}.",
                labels=["filler:trino-table-write"]))
    return out


def emrfs_filler(target_count):
    """S3 prefixes mirroring the lake layout."""
    out = []
    buckets = {
        "acme-datalake-raw": ["orders", "customers", "clickstream", "shipments"],
        "acme-datalake-refined": ["orders", "customers", "products"],
        "acme-datalake-curated": ["daily_sales", "customer_360", "product_margin"],
        "acme-datalake-finance": ["gl", "invoices"],
        "acme-datalake-marketing": ["campaigns", "attribution"],
    }
    i = 0
    for bucket, prefixes in buckets.items():
        for prefix in prefixes:
            if len(out) >= target_count:
                return out
            grp = READER_GROUPS[i % len(READER_GROUPS)]
            i += 1
            out.append(policy(
                SVC_EMRFS, f"emrfs-read-{bucket}-{prefix}",
                {"sthreeresource": res(f"{bucket}/{prefix}", recursive=True)},
                allow=[item(["GetObject", "ListObjects"], groups=[grp])],
                description=f"EMRFS read on s3://{bucket}/{prefix}/*.",
                labels=["filler:emrfs-read"]))

            if len(out) >= target_count:
                return out
            out.append(policy(
                SVC_EMRFS, f"emrfs-write-{bucket}-{prefix}",
                {"sthreeresource": res(f"{bucket}/{prefix}", recursive=True)},
                allow=[item(["GetObject", "PutObject", "ListObjects"],
                            groups=[G_ENGINEERS])],
                description=f"EMRFS write on s3://{bucket}/{prefix}/*.",
                labels=["filler:emrfs-write"]))
    return out


def build():
    policies = []
    policies += hive_showcase()
    policies += hive_filler(62)
    policies += trino_showcase()
    policies += trino_filler(45)
    policies += emrfs_showcase()
    policies += emrfs_filler(26)
    policies += tag_policies()

    return {
        "metaDataInfo": {
            "Host name": "ranger-admin.acme.internal",
            "Exported by": "admin",
            "Export time": "Sep 11, 2026 9:00:00 AM",
            "Ranger apache version": "2.4.0",
        },
        "policies": policies,
        "startIndex": 0,
        "pageSize": len(policies),
        "totalCount": len(policies),
        "resultSize": len(policies),
        "queryTimeMS": 41,
    }


def main():
    export = build()
    OUT_PATH.write_text(json.dumps(export, indent=2) + "\n", encoding="utf-8")

    by_service = {}
    for p in export["policies"]:
        by_service[p["service"]] = by_service.get(p["service"], 0) + 1

    print(f"Wrote {OUT_PATH} — {export['totalCount']} policies")
    for svc, count in by_service.items():
        print(f"  {svc:<12} ({SERVICE_TYPE[svc]:<17}) {count:>4}")

    showcase = sum(1 for p in export["policies"]
                   if any(l.startswith("demo:") for l in p["policyLabels"]))
    gaps = sum(1 for p in export["policies"]
               if any(l.startswith("gap:") for l in p["policyLabels"]))
    print(f"  showcase policies: {showcase}")
    print(f"  deliberate gaps:   {gaps}")


if __name__ == "__main__":
    main()
