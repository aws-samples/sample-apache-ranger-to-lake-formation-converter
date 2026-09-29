#!/usr/bin/env python3
"""Generate the Identity Center demo fixture.

Emits three files that are meant to be used together:

    Idc_Demo_Ranger_Policies.json   a Ranger Admin export (hive + trino)
    idc-demo-resources.yaml         CloudFormation: IDC instance, users, groups,
                                    Glue databases, tables, and an S3 bucket
    idc-demo-config.yaml            assessment config with IDENTITY_CENTER mapping

The point of the fixture is to exercise IdentityCenterPrincipalMapper against a
live identity store: every Ranger user name equals an Identity Center `userName`
and every Ranger group name equals a group `displayName`, so the mapper's
GetUserId / GetGroupId lookups resolve with no name normalisation in between.

CATALOG below is the single source of truth for both the Glue tables in the
template and the resource names in the policies, so a policy cannot reference a
database, table, or column that does not exist in the catalog.

Regenerate with:  python3 demo/idc/generate_idc_demo.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from ranger_export import PolicyBuilder, export, item, res  # noqa: E402

HERE = Path(__file__).resolve().parent
POLICIES_PATH = HERE / "Idc_Demo_Ranger_Policies.json"
TEMPLATE_PATH = HERE / "idc-demo-resources.yaml"
CONFIG_PATH = HERE / "idc-demo-config.yaml"

ACCOUNT_ID = "427250191192"
REGION = "us-east-1"

SVC_HIVE = "acme_hive"
SVC_TRINO = "acme_trino"
GDC_CATALOG = "awsdatacatalog"  # must match gdcCatalogName in idc-demo-config.yaml

SERVICE_TYPE = {SVC_HIVE: "hive", SVC_TRINO: "trino"}
ID_BASE = {SVC_HIVE: 1000, SVC_TRINO: 2000}

_builder = PolicyBuilder(SERVICE_TYPE, ID_BASE, guid_namespace="acme-idc")
policy = _builder.policy

TARGET_HIVE = 90
TARGET_TRINO = 60

# ======================================================================================
# Principals
#
# Plain short names, matching the style of the offline demo fixture. Each name is
# created verbatim in Identity Center: users as `userName`, groups as `displayName`.
# ======================================================================================

# group display name -> description
GROUPS = {
    "data_analysts": "Self-service analysts querying curated marts",
    "data_engineers": "Owns the raw and refined layers",
    "bi_developers": "Builds dashboards on top of curated tables",
    "ml_engineers": "Trains models on curated and refined features",
    "finance_readers": "Read-only access to the finance domain",
    "finance_admins": "Owns the finance domain schema",
    "hr_readers": "Read-only access to non-sensitive HR tables",
    "hr_admins": "Owns the HR domain, including compensation",
    "marketing_bi": "Campaign and attribution reporting",
    "clinical_researchers": "Clinical trial analysis, no direct patient PII",
    "data_stewards": "Catalog governance and grant delegation",
    "audit_readers": "Read-only oversight across every domain",
    "platform_admins": "Lake platform operations",
    "external_partners": "Third-party suppliers, tightly scoped",
}

# user name -> (given name, family name, [group display names])
USERS = {
    "alice_analyst": ("Alice", "Nguyen", ["data_analysts", "bi_developers"]),
    "frank_analyst": ("Frank", "Okoro", ["data_analysts"]),
    "grace_analyst": ("Grace", "Lindqvist", ["data_analysts", "marketing_bi"]),
    "henry_analyst": ("Henry", "Alvarez", ["data_analysts", "finance_readers"]),
    "bob_engineer": ("Bob", "Tanaka", ["data_engineers", "platform_admins"]),
    "ivan_engineer": ("Ivan", "Petrov", ["data_engineers"]),
    "judy_engineer": ("Judy", "Castellanos", ["data_engineers", "ml_engineers"]),
    "karl_engineer": ("Karl", "Bauer", ["data_engineers"]),
    "laura_finance": ("Laura", "Mbeki", ["finance_admins", "finance_readers"]),
    "mike_finance": ("Mike", "Donnelly", ["finance_readers"]),
    "nina_finance": ("Nina", "Haddad", ["finance_readers", "audit_readers"]),
    "olivia_hr": ("Olivia", "Rasmussen", ["hr_admins", "hr_readers"]),
    "peter_hr": ("Peter", "Ferreira", ["hr_readers"]),
    "quinn_marketing": ("Quinn", "Abebe", ["marketing_bi", "bi_developers"]),
    "rita_marketing": ("Rita", "Sokolova", ["marketing_bi"]),
    "sam_marketing": ("Sam", "Whitfield", ["marketing_bi"]),
    "tina_partner": ("Tina", "Kovalenko", ["external_partners"]),
    "umar_partner": ("Umar", "Sallah", ["external_partners"]),
    "dave_contractor": ("Dave", "Lindgren", ["external_partners"]),
    "erin_steward": ("Erin", "Byrne", ["data_stewards"]),
    "victor_steward": ("Victor", "Ivanov", ["data_stewards", "audit_readers"]),
    "carol_dpo": ("Carol", "Mensah", ["data_stewards", "audit_readers"]),
    "wendy_admin": ("Wendy", "Chaudhry", ["platform_admins"]),
    "xavier_sre": ("Xavier", "Duarte", ["platform_admins", "ml_engineers"]),
}

# Identity Center has no notion of a Ranger role, so IdentityCenterPrincipalMapper
# always returns empty for one. Kept deliberately to exercise that path.
UNMAPPABLE_ROLE = "lf_data_steward"

EMAIL_DOMAIN = "corp.acme.example"

# ======================================================================================
# Catalog
#
# Single source of truth for the Glue tables in the template and for the database,
# table, and column names the policies reference.
# ======================================================================================

_AUDIT = [("created_at", "timestamp"), ("updated_at", "timestamp")]

CATALOG = {
    "acme_raw": {
        "orders_raw": [("order_id", "string"), ("customer_id", "string"),
                       ("order_ts", "timestamp"), ("amount_usd", "double"),
                       ("payload", "string")] + _AUDIT,
        "customers_raw": [("customer_id", "string"), ("email", "string"),
                          ("phone", "string"), ("country", "string"),
                          ("payload", "string")] + _AUDIT,
        "clickstream_raw": [("event_id", "string"), ("session_id", "string"),
                            ("user_agent", "string"), ("url", "string"),
                            ("event_ts", "timestamp")] + _AUDIT,
        "shipments_raw": [("shipment_id", "string"), ("order_id", "string"),
                          ("carrier", "string"), ("status", "string")] + _AUDIT,
        "inventory_raw": [("sku", "string"), ("warehouse_id", "string"),
                          ("on_hand", "int")] + _AUDIT,
        "payments_raw": [("payment_id", "string"), ("order_id", "string"),
                         ("card_number", "string"), ("amount_usd", "double"),
                         ("processor", "string")] + _AUDIT,
        "returns_raw": [("return_id", "string"), ("order_id", "string"),
                        ("reason_code", "string")] + _AUDIT,
        "suppliers_raw": [("supplier_id", "string"), ("name", "string"),
                          ("tax_id", "string"), ("country", "string")] + _AUDIT,
    },
    "acme_refined": {
        "orders": [("order_id", "string"), ("customer_id", "string"),
                   ("order_date", "date"), ("amount_usd", "double"),
                   ("status", "string")] + _AUDIT,
        "customers": [("customer_id", "string"), ("email", "string"),
                      ("phone", "string"), ("ssn", "string"),
                      ("country", "string"), ("segment", "string")] + _AUDIT,
        "products": [("sku", "string"), ("product_name", "string"),
                     ("category", "string"), ("list_price_usd", "double")] + _AUDIT,
        "shipments": [("shipment_id", "string"), ("order_id", "string"),
                      ("carrier", "string"), ("delivered_date", "date")] + _AUDIT,
        "returns": [("return_id", "string"), ("order_id", "string"),
                    ("reason_code", "string"), ("refund_usd", "double")] + _AUDIT,
        "inventory": [("sku", "string"), ("warehouse_id", "string"),
                      ("on_hand", "int"), ("reorder_point", "int")] + _AUDIT,
        "payments": [("payment_id", "string"), ("order_id", "string"),
                     ("card_number", "string"), ("amount_usd", "double"),
                     ("status", "string")] + _AUDIT,
        "suppliers": [("supplier_id", "string"), ("name", "string"),
                      ("tax_id", "string"), ("country", "string"),
                      ("rating", "double")] + _AUDIT,
        "price_history": [("sku", "string"), ("effective_date", "date"),
                          ("price_usd", "double")] + _AUDIT,
    },
    "acme_curated": {
        "daily_sales": [("sales_date", "date"), ("region", "string"),
                        ("orders", "bigint"), ("revenue_usd", "double")] + _AUDIT,
        "customer_360": [("customer_id", "string"), ("email", "string"),
                         ("phone", "string"), ("region", "string"),
                         ("segment", "string"), ("lifetime_value", "double")] + _AUDIT,
        "product_margin": [("sku", "string"), ("category", "string"),
                           ("margin_pct", "double"), ("revenue_usd", "double")] + _AUDIT,
        "churn_scores": [("customer_id", "string"), ("score", "double"),
                         ("model_version", "string")] + _AUDIT,
        "cohort_retention": [("cohort_month", "string"), ("month_offset", "int"),
                             ("retained_pct", "double")] + _AUDIT,
        "supplier_scorecard": [("supplier_id", "string"), ("on_time_pct", "double"),
                               ("defect_rate", "double")] + _AUDIT,
        "inventory_health": [("sku", "string"), ("warehouse_id", "string"),
                             ("days_of_cover", "double")] + _AUDIT,
    },
    "finance": {
        "gl_entries": [("entry_id", "string"), ("account_code", "string"),
                       ("amount_usd", "double"), ("posted_date", "date")] + _AUDIT,
        "invoices": [("invoice_id", "string"), ("customer_id", "string"),
                     ("amount_usd", "double"), ("due_date", "date"),
                     ("status", "string")] + _AUDIT,
        "revenue_recognition": [("contract_id", "string"), ("period", "string"),
                                ("recognized_usd", "double")] + _AUDIT,
        "cost_centers": [("cost_center", "string"), ("owner", "string"),
                         ("budget_usd", "double")] + _AUDIT,
        "budget_actuals": [("cost_center", "string"), ("period", "string"),
                           ("budget_usd", "double"), ("actual_usd", "double")] + _AUDIT,
        "fx_rates": [("currency", "string"), ("rate_date", "date"),
                     ("rate_to_usd", "double")] + _AUDIT,
        "tax_provisions": [("jurisdiction", "string"), ("period", "string"),
                           ("provision_usd", "double")] + _AUDIT,
    },
    "hr": {
        "employees": [("employee_id", "string"), ("full_name", "string"),
                      ("email", "string"), ("ssn", "string"),
                      ("department", "string"), ("hire_date", "date")] + _AUDIT,
        "compensation": [("employee_id", "string"), ("salary", "double"),
                         ("bonus_usd", "double"), ("equity_units", "double"),
                         ("pay_grade", "string")] + _AUDIT,
        "headcount_plan": [("department", "string"), ("period", "string"),
                           ("planned_heads", "int")] + _AUDIT,
        "org_hierarchy": [("employee_id", "string"), ("manager_id", "string"),
                          ("depth", "int")] + _AUDIT,
        "training_records": [("employee_id", "string"), ("course_code", "string"),
                             ("completed_date", "date")] + _AUDIT,
    },
    "marketing": {
        "campaigns": [("campaign_id", "string"), ("campaign_name", "string"),
                      ("channel", "string"), ("start_date", "date"),
                      ("budget_usd", "double")] + _AUDIT,
        "attribution": [("campaign_id", "string"), ("channel", "string"),
                        ("conversions", "bigint"), ("spend_usd", "double")] + _AUDIT,
        "audience_segments": [("segment_id", "string"), ("segment_name", "string"),
                              ("size", "bigint")] + _AUDIT,
        "spend_by_channel": [("channel", "string"), ("period", "string"),
                             ("spend_usd", "double")] + _AUDIT,
        "email_engagement": [("campaign_id", "string"), ("email", "string"),
                             ("opened", "boolean"), ("clicked", "boolean")] + _AUDIT,
        "web_sessions": [("session_id", "string"), ("customer_id", "string"),
                         ("duration_sec", "int"), ("pages", "int")] + _AUDIT,
    },
    "clinical": {
        "trial_sites": [("site_id", "string"), ("site_name", "string"),
                        ("country", "string"), ("principal_investigator", "string")] + _AUDIT,
        "trial_results": [("trial_id", "string"), ("site_id", "string"),
                          ("arm", "string"), ("endpoint_met", "boolean")] + _AUDIT,
        "patient_pii": [("patient_id", "string"), ("full_name", "string"),
                        ("date_of_birth", "date"), ("ssn", "string"),
                        ("diagnosis_code", "string")] + _AUDIT,
    },
}

DATABASES = list(CATALOG)


def cols(db, table):
    """Column names for a table, in catalog order."""
    return [c for c, _ in CATALOG[db][table]]


# ======================================================================================
# Hive policies
#
# Hive access-type mapping (HiveServiceAdapter):
#   select -> SELECT   update -> INSERT   create -> CREATE_TABLE
#   drop   -> DROP     alter  -> ALTER    all    -> ALL
#   read   -> SELECT   write  -> INSERT
# Each Cedar action is then filtered by resource level, so a database-level policy
# must carry ONLY the `database` key and use create/alter/drop/all — SELECT is not a
# valid database-level LF permission.
# ======================================================================================

def hive_showcase():
    p = []

    p.append(policy(
        SVC_HIVE, "idc-curated-db-owner",
        {"database": res("acme_curated")},
        allow=[item(["create", "alter", "drop"], groups=["data_engineers"])],
        description="Database-level ownership granted to an Identity Center group. "
                    "The group name resolves via GetGroupId on displayName.",
        labels=["idc:group-principal", "demo:database-level"]))

    p.append(policy(
        SVC_HIVE, "idc-daily-sales-read",
        {"database": res("acme_curated"), "table": res("daily_sales"),
         "column": res("*")},
        allow=[item(["select"], groups=["data_analysts", "bi_developers"],
                    users=["alice_analyst"])],
        description="One policy item carrying two groups and one user — three "
                    "separate IdentityStore lookups, three LF grants.",
        labels=["idc:mixed-principals", "demo:table-level"]))

    p.append(policy(
        SVC_HIVE, "idc-customer360-nonpii-columns",
        {"database": res("acme_curated"), "table": res("customer_360"),
         "column": res(["customer_id", "region", "segment", "lifetime_value"])},
        allow=[item(["select"], groups=["marketing_bi"])],
        description="Column-level read -> LF TABLE_WITH_COLUMNS listing only the four "
                    "permitted columns. email and phone stay out.",
        labels=["demo:column-level"]))

    p.append(policy(
        SVC_HIVE, "idc-customer360-exclude-pii",
        {"database": res("acme_curated"), "table": res("customer_360"),
         "column": res(["email", "phone"], excludes=True)},
        allow=[item(["select"], groups=["data_analysts"])],
        description="isExcludes column policy. The converter expands the exclusion "
                    "against the real Glue table schema.",
        labels=["demo:column-excludes"]))

    p.append(policy(
        SVC_HIVE, "idc-refined-read-all-tables",
        {"database": res("acme_refined"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=["data_analysts", "ml_engineers"])],
        description="Wildcard table pattern. With --aws-profile set, this expands "
                    "against the live catalog instead of reporting WILDCARD_PATTERN.",
        labels=["demo:wildcard-expansion"]))

    p.append(policy(
        SVC_HIVE, "idc-raw-engineer-full",
        {"database": res("acme_raw"), "table": res("*"), "column": res("*")},
        allow=[item(["all"], groups=["data_engineers"])],
        description="'all' on every raw table -> LF ALL.",
        labels=["demo:all-access"]))

    p.append(policy(
        SVC_HIVE, "idc-steward-delegation",
        {"database": res("acme_curated"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=["data_stewards"], delegate_admin=True)],
        description="delegateAdmin -> grantable LF permissions for the steward group.",
        labels=["demo:grantable"]))

    p.append(policy(
        SVC_HIVE, "idc-hr-compensation-restricted",
        {"database": res("hr"), "table": res("compensation"), "column": res("*")},
        allow=[item(["select"], groups=["hr_admins"], users=["olivia_hr"])],
        description="Sensitive HR table restricted to the HR admin group.",
        labels=["idc:mixed-principals"]))

    p.append(policy(
        SVC_HIVE, "idc-hr-employees-nonsensitive",
        {"database": res("hr"), "table": res("employees"),
         "column": res(["ssn", "email"], excludes=True)},
        allow=[item(["select"], groups=["hr_readers"])],
        description="HR readers see employees without ssn or email.",
        labels=["demo:column-excludes"]))

    p.append(policy(
        SVC_HIVE, "idc-hr-employees-allowlist",
        {"database": res("hr"), "table": res("employees"),
         "column": res(["employee_id", "full_name", "department", "hire_date"])},
        allow=[item(["select"], groups=["hr_readers"])],
        description="The allowlist rewrite of idc-hr-employees-nonsensitive. Same "
                    "intent, stated as columns to include rather than exclude, so it "
                    "converts cleanly — this is the remediation for EXCLUDES_PATTERN.",
        labels=["demo:excludes-remediation", "demo:column-level"]))

    p.append(policy(
        SVC_HIVE, "idc-finance-domain-owner",
        {"database": res("finance")},
        allow=[item(["create", "alter", "drop"], groups=["finance_admins"],
                    delegate_admin=True)],
        description="Finance domain ownership with delegation.",
        labels=["demo:database-level", "demo:grantable"]))

    p.append(policy(
        SVC_HIVE, "idc-finance-readers-select",
        {"database": res("finance"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=["finance_readers"]),
               item(["select"], users=["nina_finance", "henry_analyst"])],
        description="Two policy items on one policy: a group grant and a per-user "
                    "grant. Every principal resolves independently.",
        labels=["idc:multi-item"]))

    p.append(policy(
        SVC_HIVE, "idc-payments-card-denied",
        {"database": res("acme_refined"), "table": res("payments"),
         "column": res("*")},
        allow=[item(["select"], groups=["data_analysts"])],
        deny=[item(["select"], groups=["external_partners"])],
        description="A deny item alongside an allow. Lake Formation has no deny, so "
                    "the deny is reported as a gap rather than silently dropped.",
        labels=["gap:deny-semantics"]))

    p.append(policy(
        SVC_HIVE, "idc-clinical-patient-pii-locked",
        {"database": res("clinical"), "table": res("patient_pii"),
         "column": res("*")},
        allow=[item(["select"], users=["carol_dpo"])],
        description="Single-user grant on the most sensitive table — exercises "
                    "GetUserId on userName.",
        labels=["idc:user-principal"]))

    p.append(policy(
        SVC_HIVE, "idc-clinical-results-research",
        {"database": res("clinical"),
         "table": res(["trial_sites", "trial_results"]), "column": res("*")},
        allow=[item(["select"], groups=["clinical_researchers"])],
        description="Multi-valued table resource -> one LF grant per named table.",
        labels=["demo:multi-table"]))

    p.append(policy(
        SVC_HIVE, "idc-marketing-attribution-write",
        {"database": res("marketing"), "table": res("attribution"),
         "column": res("*")},
        allow=[item(["select", "update"], groups=["marketing_bi"])],
        description="select + update -> LF SELECT + INSERT.",
        labels=["demo:write-access"]))

    p.append(policy(
        SVC_HIVE, "idc-audit-read-everything",
        {"database": res("*"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], groups=["audit_readers"])],
        description="Database wildcard for oversight — the broadest policy in the "
                    "fixture. Expands across all seven databases.",
        labels=["demo:wildcard-expansion"]))

    p.append(policy(
        SVC_HIVE, "idc-platform-admin-all",
        {"database": res("*"), "table": res("*"), "column": res("*")},
        allow=[item(["all"], groups=["platform_admins"], delegate_admin=True)],
        description="Platform-wide ALL with delegation.",
        labels=["demo:all-access", "demo:grantable"]))

    p.append(policy(
        SVC_HIVE, "idc-partner-supplier-scoped",
        {"database": res("acme_curated"), "table": res("supplier_scorecard"),
         "column": res(["supplier_id", "on_time_pct"])},
        allow=[item(["select"], groups=["external_partners"],
                    users=["tina_partner", "umar_partner"])],
        description="Tightly scoped partner access — two columns only.",
        labels=["demo:column-level", "idc:mixed-principals"]))

    # Direct per-user grants. Most principals above are groups, which is how a real
    # deployment looks — but every Identity Center user the template creates should be
    # resolved by at least one policy, otherwise the demo creates identities the
    # assessment never looks up.

    p.append(policy(
        SVC_HIVE, "idc-raw-layer-owners",
        {"database": res("acme_raw")},
        allow=[item(["create", "alter", "drop"],
                    users=["bob_engineer", "ivan_engineer", "karl_engineer"],
                    delegate_admin=True)],
        description="Named raw-layer owners rather than a group — three GetUserId "
                    "lookups, three grantable LF DATABASE grants.",
        labels=["idc:user-principal", "demo:grantable"]))

    p.append(policy(
        SVC_HIVE, "idc-finance-gl-owner",
        {"database": res("finance"), "table": res("gl_entries"), "column": res("*")},
        allow=[item(["select", "update"], users=["laura_finance"])],
        description="Single named owner of the general ledger.",
        labels=["idc:user-principal"]))

    p.append(policy(
        SVC_HIVE, "idc-steward-pii-review",
        {"database": res("acme_refined"), "table": res("customers"),
         "column": res(["customer_id", "email", "ssn"])},
        allow=[item(["select"], users=["erin_steward", "victor_steward"])],
        description="Stewards review the PII columns directly, by name.",
        labels=["idc:user-principal", "demo:column-level"]))

    p.append(policy(
        SVC_HIVE, "idc-platform-admin-hr-ops",
        {"database": res("hr")},
        allow=[item(["alter"], users=["wendy_admin"])],
        description="Platform operator with schema-maintenance rights on HR.",
        labels=["idc:user-principal", "demo:database-level"]))

    p.append(policy(
        SVC_HIVE, "idc-contractor-scoped-read",
        {"database": res("acme_curated"), "table": res("daily_sales"),
         "column": res(["sales_date", "region", "revenue_usd"])},
        allow=[item(["select"], users=["dave_contractor"])],
        description="Contractor limited to three columns of one curated table.",
        labels=["idc:user-principal", "demo:column-level"]))

    p.append(policy(
        SVC_HIVE, "idc-role-grant-unmappable",
        {"database": res("acme_curated"), "table": res("*"), "column": res("*")},
        allow=[item(["select"], roles=[UNMAPPABLE_ROLE])],
        description="Grants to a Ranger ROLE. Identity Center has no role principal, "
                    "so IdentityCenterPrincipalMapper returns empty and this becomes "
                    "an unmapped-principal gap. Deliberate.",
        labels=["gap:role-unmappable", "idc:role"]))

    p.append(policy(
        SVC_HIVE, "idc-disabled-policy",
        {"database": res("finance"), "table": res("gl_entries"), "column": res("*")},
        allow=[item(["select"], groups=["external_partners"])],
        description="Disabled in Ranger, so it must contribute no LF grant.",
        labels=["demo:disabled"],
        enabled=False))

    return p


def trino_showcase():
    p = []

    p.append(policy(
        SVC_TRINO, "idc-trino-curated-browse",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_curated")},
        allow=[item(["use", "show"], groups=["data_analysts"])],
        description="Trino's 'schema' key maps to a Glue database; use/show convert "
                    "to LF DESCRIBE, which Hive access types cannot express.",
        labels=["demo:trino-schema-key", "demo:describe"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-daily-sales-read",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_curated"),
         "table": res("daily_sales"), "column": res("*")},
        allow=[item(["select"], groups=["data_analysts", "marketing_bi"])],
        description="Trino table read under the Glue catalog.",
        labels=["demo:trino-table-level"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-refined-writeback",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_refined"),
         "table": res("returns"), "column": res("*")},
        allow=[item(["select", "insert", "delete"], groups=["data_engineers"])],
        description="Trino 'delete' -> LF DELETE, unreachable through Hive access types.",
        labels=["demo:trino-delete"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-attribution-columns",
        {"catalog": res(GDC_CATALOG), "schema": res("marketing"),
         "table": res("attribution"),
         "column": res(["campaign_id", "channel", "conversions", "spend_usd"])},
        allow=[item(["select"], groups=["marketing_bi"], users=["quinn_marketing"])],
        description="Trino column-level read -> LF TABLE_WITH_COLUMNS.",
        labels=["demo:trino-column-level", "idc:mixed-principals"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-schema-owner-refined",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_refined")},
        allow=[item(["create", "alter", "drop"], groups=["data_engineers"],
                    delegate_admin=True)],
        description="Schema-level ownership with delegateAdmin -> grantable LF "
                    "DATABASE permissions.",
        labels=["demo:trino-schema-owner", "demo:grantable"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-all-schemas-read",
        {"catalog": res(GDC_CATALOG), "schema": res("*"), "table": res("*"),
         "column": res("*")},
        allow=[item(["select"], groups=["audit_readers"])],
        description="Trino wildcard schema and table — expands against the live catalog.",
        labels=["demo:wildcard-expansion"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-hr-comp-admins",
        {"catalog": res(GDC_CATALOG), "schema": res("hr"),
         "table": res("compensation"), "column": res("*")},
        allow=[item(["select"], groups=["hr_admins"])],
        description="Sensitive HR table through Trino.",
        labels=["demo:trino-table-level"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-employees-exclude-pii",
        {"catalog": res(GDC_CATALOG), "schema": res("hr"),
         "table": res("employees"),
         "column": res(["ssn", "email"], excludes=True)},
        allow=[item(["select"], groups=["hr_readers"])],
        description="Trino column exclusion resolved against the real table schema.",
        labels=["demo:column-excludes"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-ml-curated-features",
        {"catalog": res(GDC_CATALOG), "schema": res("acme_curated"),
         "table": res(["churn_scores", "cohort_retention", "product_margin"]),
         "column": res("*")},
        allow=[item(["select"], groups=["ml_engineers"],
                    users=["judy_engineer", "xavier_sre"])],
        description="Feature tables for model training.",
        labels=["demo:multi-table", "idc:mixed-principals"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-platform-all",
        {"catalog": res(GDC_CATALOG), "schema": res("*"), "table": res("*"),
         "column": res("*")},
        allow=[item(["all"], groups=["platform_admins"])],
        description="Trino 'all' -> LF ALL across the catalog.",
        labels=["demo:all-access"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-nonglue-catalog-filtered",
        {"catalog": res("postgresql_crm"), "schema": res("public"),
         "table": res("accounts"), "column": res("*")},
        allow=[item(["select"], groups=["data_analysts"])],
        description="A Trino policy on a NON-Glue catalog. Because gdcCatalogName is "
                    "'awsdatacatalog', the converter filters this out rather than "
                    "inventing a grant for a federated Postgres table. Correct, not a gap.",
        labels=["demo:catalog-filtered"]))

    p.append(policy(
        SVC_TRINO, "idc-trino-url-data-location",
        {"url": res(f"s3://acme-idc-demo-{ACCOUNT_ID}/refined/*")},
        allow=[item(["select"], groups=["data_engineers"])],
        description="Trino 'url' resource -> LF DATA_LOCATION_ACCESS.",
        labels=["demo:trino-url", "demo:data-location"]))

    return p


# ======================================================================================
# Filler policies
#
# These pad the export to a realistic size so assessment counts are meaningful.
# Every resource they name comes from CATALOG, so nothing dangles.
# ======================================================================================

READER_GROUPS = ["data_analysts", "bi_developers", "finance_readers", "marketing_bi",
                 "ml_engineers", "audit_readers", "clinical_researchers"]
READER_USERS = ["alice_analyst", "frank_analyst", "grace_analyst", "henry_analyst",
                "mike_finance", "rita_marketing", "sam_marketing", "peter_hr"]

# Filler must not hand broad access to the sensitive corners; the showcase policies
# above own those, and contradicting them would muddy the demo.
FILLER_EXCLUDED_DBS = {"clinical"}
FILLER_EXCLUDED_TABLES = {
    ("hr", "compensation"),
    ("hr", "employees"),
    ("acme_refined", "customers"),
    ("acme_refined", "payments"),
    ("acme_raw", "payments_raw"),
    ("acme_raw", "customers_raw"),
    ("acme_curated", "customer_360"),
}


def _filler_targets():
    """(db, table) pairs the filler may grant on, in a stable order."""
    out = []
    for db in DATABASES:
        if db in FILLER_EXCLUDED_DBS:
            continue
        for table in CATALOG[db]:
            if (db, table) not in FILLER_EXCLUDED_TABLES:
                out.append((db, table))
    return out


def hive_filler(target_count):
    p = []
    targets = _filler_targets()
    i = 0
    while len(p) < target_count:
        db, table = targets[i % len(targets)]
        n = len(p)
        group = READER_GROUPS[n % len(READER_GROUPS)]
        user = READER_USERS[n % len(READER_USERS)]
        i += 1

        if n % 5 == 4:
            # Column-scoped: first three non-audit columns.
            columns = [c for c in cols(db, table)
                       if c not in ("created_at", "updated_at")][:3]
            p.append(policy(
                SVC_HIVE, f"filler-hive-{db}-{table}-cols",
                {"database": res(db), "table": res(table), "column": res(columns)},
                allow=[item(["select"], groups=[group])],
                description=f"Column-scoped read on {db}.{table}.",
                labels=["filler:hive-columns"]))
        elif n % 5 == 3:
            p.append(policy(
                SVC_HIVE, f"filler-hive-{db}-{table}-write",
                {"database": res(db), "table": res(table), "column": res("*")},
                allow=[item(["select", "update"], groups=["data_engineers"])],
                description=f"Read/write on {db}.{table}.",
                labels=["filler:hive-write"]))
        else:
            p.append(policy(
                SVC_HIVE, f"filler-hive-{db}-{table}-read-{n}",
                {"database": res(db), "table": res(table), "column": res("*")},
                allow=[item(["select"], groups=[group], users=[user])],
                description=f"Read on {db}.{table}.",
                labels=["filler:hive-read"]))
    return p


def trino_filler(target_count):
    p = []
    targets = _filler_targets()
    i = 0
    while len(p) < target_count:
        db, table = targets[i % len(targets)]
        n = len(p)
        group = READER_GROUPS[n % len(READER_GROUPS)]
        user = READER_USERS[(n + 3) % len(READER_USERS)]
        i += 1

        if n % 4 == 3:
            columns = [c for c in cols(db, table)
                       if c not in ("created_at", "updated_at")][:2]
            p.append(policy(
                SVC_TRINO, f"filler-trino-{db}-{table}-cols",
                {"catalog": res(GDC_CATALOG), "schema": res(db),
                 "table": res(table), "column": res(columns)},
                allow=[item(["select"], groups=[group])],
                description=f"Trino column-scoped read on {db}.{table}.",
                labels=["filler:trino-columns"]))
        elif n % 4 == 2:
            p.append(policy(
                SVC_TRINO, f"filler-trino-{db}-browse-{n}",
                {"catalog": res(GDC_CATALOG), "schema": res(db)},
                allow=[item(["use", "show"], groups=[group], users=[user])],
                description=f"Trino schema browse on {db}.",
                labels=["filler:trino-browse"]))
        else:
            p.append(policy(
                SVC_TRINO, f"filler-trino-{db}-{table}-read-{n}",
                {"catalog": res(GDC_CATALOG), "schema": res(db),
                 "table": res(table), "column": res("*")},
                allow=[item(["select"], groups=[group], users=[user])],
                description=f"Trino read on {db}.{table}.",
                labels=["filler:trino-read"]))
    return p


def build_policies():
    policies = []
    policies += hive_showcase()
    policies += trino_showcase()
    hive_so_far = sum(1 for p in policies if p["service"] == SVC_HIVE)
    trino_so_far = sum(1 for p in policies if p["service"] == SVC_TRINO)
    policies += hive_filler(TARGET_HIVE - hive_so_far)
    policies += trino_filler(TARGET_TRINO - trino_so_far)

    return export(policies,
                  host_name="ranger-admin.acme.internal",
                  exported_by="admin",
                  export_time="Sep 29, 2026 9:00:00 AM")


# ======================================================================================
# CloudFormation template
#
# Hand-assembled YAML rather than a dict dumped through a YAML library, because the
# template needs !Ref / !GetAtt short tags and readable comments.
# ======================================================================================

def _logical(prefix, name):
    """A CloudFormation logical ID: alphanumeric only, stable across regenerations."""
    parts = [w.capitalize() for w in name.replace("-", "_").split("_") if w]
    return prefix + "".join(parts)


def _glue_columns(columns):
    return "\n".join(f"              - Name: {c}\n                Type: {t}"
                     for c, t in columns)


def build_template():
    L = []
    a = L.append

    # Every user, group, and membership reads the store ID through this, so the
    # template works whether the instance is created here or passed in.
    STORE_ID = ("!If [CreateIdcInstance, !GetAtt IdcInstance.IdentityStoreId, "
                "!Ref ExistingIdentityStoreId]")

    a("AWSTemplateFormatVersion: '2010-09-09'")
    a("")
    a("Description: >-")
    a("  Identity Center demo fixture for the Ranger -> Lake Formation assessment tool.")
    a("  Creates Identity Center users and groups named identically to the principals in")
    a("  Idc_Demo_Ranger_Policies.json, plus the Glue databases, tables, and columns")
    a("  those policies reference.")
    a("")
    a("  Generated by demo/idc/generate_idc_demo.py - do not edit by hand.")
    a("")
    a("Parameters:")
    a("")
    a("  ExistingIdentityStoreId:")
    a("    Type: String")
    a("    Default: ''")
    a("    Description: >-")
    a("      Identity store ID of an Identity Center instance that already exists")
    a("      (e.g. d-1234567890). Leave empty to have this stack create an")
    a("      ACCOUNT-level instance instead.")
    a("")
    a("      An account instance CANNOT be created from an AWS Organizations")
    a("      management account - CreateInstance rejects it. If this account manages an")
    a("      organization, enable an ORGANIZATION instance in the Identity Center")
    a("      console first and pass its identity store ID here. Doing so also keeps the")
    a("      instance outside this stack's lifecycle, so deleting the stack leaves it")
    a("      intact.")
    a("")
    a("  DataBucketName:")
    a("    Type: String")
    a(f"    Default: acme-idc-demo-{ACCOUNT_ID}")
    a("    Description: >-")
    a("      Bucket backing the Glue table locations. No objects are written; the")
    a("      tables exist so the assessment can expand wildcards against real metadata.")
    a("")
    a("Conditions:")
    a("")
    a("  CreateIdcInstance: !Equals [!Ref ExistingIdentityStoreId, '']")
    a("")
    a("Resources:")
    a("")
    a("  # ------------------------------------------------------------------")
    a("  # Identity Center")
    a("  # ------------------------------------------------------------------")
    a("")
    a("  IdcInstance:")
    a("    Type: AWS::SSO::Instance")
    a("    Condition: CreateIdcInstance")
    a("    Properties:")
    a("      Name: acme-ranger-lf-demo")
    a("      Tags:")
    a("        - Key: Purpose")
    a("          Value: ranger-to-lakeformation-demo")
    a("")

    a("  # Groups. DisplayName is what IdentityCenterPrincipalMapper passes to")
    a("  # GetGroupId, so each one equals a Ranger group name exactly.")
    a("")
    for group, description in GROUPS.items():
        a(f"  {_logical('Group', group)}:")
        a("    Type: AWS::IdentityStore::Group")
        a("    Properties:")
        a(f"      IdentityStoreId: {STORE_ID}")
        a(f"      DisplayName: {group}")
        a(f"      Description: {description}")
        a("")

    a("  # Users. UserName is what IdentityCenterPrincipalMapper passes to GetUserId.")
    a("")
    for user, (given, family, _) in USERS.items():
        a(f"  {_logical('User', user)}:")
        a("    Type: AWS::IdentityStore::User")
        a("    Properties:")
        a(f"      IdentityStoreId: {STORE_ID}")
        a(f"      UserName: {user}")
        a(f"      DisplayName: {given} {family}")
        a("      Name:")
        a(f"        GivenName: {given}")
        a(f"        FamilyName: {family}")
        a("      Emails:")
        a(f"        - Value: {user}@{EMAIL_DOMAIN}")
        a("          Type: work")
        a("          Primary: true")
        a("")

    a("  # Group memberships.")
    a("")
    for user, (_, _, groups) in USERS.items():
        for group in groups:
            a(f"  {_logical('Member', user)}In{_logical('', group)}:")
            a("    Type: AWS::IdentityStore::GroupMembership")
            a("    Properties:")
            a(f"      IdentityStoreId: {STORE_ID}")
            a(f"      GroupId: !GetAtt {_logical('Group', group)}.GroupId")
            a("      MemberId:")
            a(f"        UserId: !GetAtt {_logical('User', user)}.UserId")
            a("")

    a("  # ------------------------------------------------------------------")
    a("  # Data lake storage")
    a("  # ------------------------------------------------------------------")
    a("")
    a("  DataBucket:")
    a("    Type: AWS::S3::Bucket")
    a("    Properties:")
    a("      BucketName: !Ref DataBucketName")
    a("      PublicAccessBlockConfiguration:")
    a("        BlockPublicAcls: true")
    a("        BlockPublicPolicy: true")
    a("        IgnorePublicAcls: true")
    a("        RestrictPublicBuckets: true")
    a("      BucketEncryption:")
    a("        ServerSideEncryptionConfiguration:")
    a("          - ServerSideEncryptionByDefault:")
    a("              SSEAlgorithm: AES256")
    a("")

    a("  # ------------------------------------------------------------------")
    a("  # Glue Data Catalog")
    a("  # ------------------------------------------------------------------")
    a("")
    for db in DATABASES:
        a(f"  {_logical('Db', db)}:")
        a("    Type: AWS::Glue::Database")
        a("    Properties:")
        a("      CatalogId: !Ref AWS::AccountId")
        a("      DatabaseInput:")
        a(f"        Name: {db}")
        a(f"        Description: Demo database {db}")
        a(f"        LocationUri: !Sub s3://${{DataBucketName}}/{db}/")
        a("")

    for db in DATABASES:
        for table, columns in CATALOG[db].items():
            a(f"  {_logical('Tbl', db + '_' + table)}:")
            a("    Type: AWS::Glue::Table")
            a(f"    DependsOn: {_logical('Db', db)}")
            a("    Properties:")
            a("      CatalogId: !Ref AWS::AccountId")
            a(f"      DatabaseName: {db}")
            a("      TableInput:")
            a(f"        Name: {table}")
            a("        TableType: EXTERNAL_TABLE")
            a("        Parameters:")
            a("          classification: parquet")
            a("        StorageDescriptor:")
            a(f"          Location: !Sub s3://${{DataBucketName}}/{db}/{table}/")
            a("          InputFormat: "
              "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat")
            a("          OutputFormat: "
              "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat")
            a("          SerdeInfo:")
            a("            SerializationLibrary: "
              "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe")
            a("          Columns:")
            a(_glue_columns(columns))
            a("")

    a("Outputs:")
    a("")
    a("  IdentityStoreId:")
    a("    Description: Feed this into principalMapping.identityCenter.identityStoreId")
    a(f"    Value: {STORE_ID}")
    a("")
    a("  IdcInstanceArn:")
    a("    Condition: CreateIdcInstance")
    a("    Value: !Ref IdcInstance")
    a("")
    a("  DataLakeBucket:")
    a("    Value: !Ref DataBucket")
    a("")
    a("  Databases:")
    a(f"    Value: {','.join(DATABASES)}")

    return "\n".join(L) + "\n"


# ======================================================================================
# Assessment config
# ======================================================================================

def build_config():
    return f"""# Assessment configuration for the Identity Center demo fixture.
#
#   java -jar target/assessment-jar-with-dependencies.jar \\
#       file demo/idc/Idc_Demo_Ranger_Policies.json \\
#       --config demo/idc/idc-demo-config.yaml \\
#       --aws-region {REGION} --aws-profile hocanint+ranger-test1 \\
#       --console-only
#
# Unlike demo/demo-config.yaml, this one resolves principals through the live
# IdentityStore API instead of a static lookup table. Every user name below is an
# Identity Center `userName` and every group name a group `displayName`, both
# created by idc-demo-resources.yaml.
#
# identityStoreId must match the IdentityStoreId output of the deployed stack.
#
# Generated by demo/idc/generate_idc_demo.py - identityStoreId is edited in place
# after deployment.

rangerServices:
  - serviceType: hive
    serviceInstanceName: {SVC_HIVE}
  - serviceType: trino
    serviceInstanceName: {SVC_TRINO}
    gdcCatalogName: {GDC_CATALOG}

principalMapping:
  type: IDENTITY_CENTER
  idcConfig:
    identityStoreId: IDENTITY_STORE_ID_PLACEHOLDER
    region: {REGION}
    accountId: "{ACCOUNT_ID}"
    cacheTtlMinutes: 60
"""


def main():
    export_doc = build_policies()
    POLICIES_PATH.write_text(json.dumps(export_doc, indent=2) + "\n", encoding="utf-8")
    TEMPLATE_PATH.write_text(build_template(), encoding="utf-8")

    # Preserve an already-substituted identityStoreId across regenerations.
    config = build_config()
    if CONFIG_PATH.exists():
        for line in CONFIG_PATH.read_text().splitlines():
            stripped = line.strip()
            if stripped.startswith("identityStoreId:"):
                existing = stripped.split(":", 1)[1].strip()
                if existing and existing != "IDENTITY_STORE_ID_PLACEHOLDER":
                    config = config.replace("IDENTITY_STORE_ID_PLACEHOLDER", existing)
                break
    CONFIG_PATH.write_text(config, encoding="utf-8")

    policies = export_doc["policies"]
    by_service = {}
    for p in policies:
        by_service[p["service"]] = by_service.get(p["service"], 0) + 1

    tables = sum(len(t) for t in CATALOG.values())
    memberships = sum(len(g) for _, _, g in USERS.values())

    print(f"Wrote {POLICIES_PATH.name} — {len(policies)} policies")
    for svc, count in sorted(by_service.items()):
        print(f"  {svc:<12} ({SERVICE_TYPE[svc]:<6}) {count:>4}")
    print(f"Wrote {TEMPLATE_PATH.name} — "
          f"{len(GROUPS)} groups, {len(USERS)} users, {memberships} memberships, "
          f"{len(DATABASES)} databases, {tables} tables")
    print(f"Wrote {CONFIG_PATH.name}")


if __name__ == "__main__":
    main()
