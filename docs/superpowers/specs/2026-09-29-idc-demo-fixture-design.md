# Identity Center Demo Fixture — Design

**Date:** 2026-09-29
**Status:** Approved

## Problem

`IdentityCenterPrincipalMapper` resolves Ranger user and group names to Identity Center
ARNs by calling `GetUserId` (on `userName`) and `GetGroupId` (on `displayName`). Nothing
in the repository exercises that path against a live identity store: the existing demo
fixture (`demo/Demo_Ranger_Policies.json`) uses `STATIC` principal mapping with
hand-written IAM ARNs, and runs deliberately offline.

We need a second fixture that can be deployed into a real AWS account so the assessment
tool can be demonstrated resolving principals through Identity Center, with Ranger
policies that reference Glue databases, tables, and columns that actually exist.

## Scope

In scope: the assessment (read-only) path — Ranger export parsing, principal resolution
through the IdentityStore API, and wildcard expansion against a real Glue Data Catalog.

Out of scope: pushing grants into Lake Formation. Trusted identity propagation requires
an Identity Center *organization* instance and a
`CreateLakeFormationIdentityCenterConfiguration` call; the account instance this design
creates cannot support it. No Lake Formation resources are created, and the account's
`IAM_ALLOWED_PRINCIPALS` default is left untouched.

## Target environment

Account `427250191192`, region `us-east-1`, AWS profile `hocanint+ranger-test1`. The
account is the management account of its own organization (`o-svmqjjhtv3`) and holds no
Glue databases. An empty Identity Center instance (`ssoins-7223f48d0c53652d`, identity
store `d-906661a1c2`, 0 users, 0 groups) exists and is deleted before deploying, so
CloudFormation can own the instance it creates.

## Approach

Three approaches were considered:

1. Extend `demo/generate_demo_policies.py` in place. Rejected: it would change the
   committed `Demo_Ranger_Policies.json` and break the offline walkthrough that
   `demo/README.md` documents.
2. **A new `demo/idc/` fixture with the Ranger-export helpers extracted into a shared
   module.** Chosen.
3. A standalone script that copy-pastes the helpers. Rejected: guarantees the two
   fixtures drift apart in export shape.

## Components

### `demo/ranger_export.py`

The Ranger export-format helpers, lifted verbatim in behaviour from
`generate_demo_policies.py`:

- `res(values, excludes=False, recursive=False)` — a resource entry. Stateless function.
- `item(accesses, users, groups, roles, delegate_admin, conditions)` — a policy item.
  Stateless function.
- `PolicyBuilder(service_types, id_bases, guid_namespace)` — holds the per-service ID
  counters, the service-type lookup, and the GUID namespace prefix. Exposes
  `policy(...)` returning one policy dict in export format.

`generate_demo_policies.py` is refactored to import these. Its output must remain
byte-identical; this is verified by regenerating and confirming `git diff` on
`demo/Demo_Ranger_Policies.json` is empty.

### `demo/idc/generate_idc_demo.py`

Single entry point, four independent builders, emits three files. The catalog model is
the single source of truth for both the CloudFormation tables and the Ranger resource
names, so a policy can never reference a database, table, or column absent from Glue.

- `principals()` → 24 users, 14 groups, and group memberships.
- `catalog()` → 7 databases, ~45 tables, explicit column lists per table.
- `policies()` → the Ranger export (~150 policies, hive + trino).
- `template()` → the CloudFormation template.

### `demo/idc/Idc_Demo_Ranger_Policies.json`

~150 policies across two Ranger services (`acme_hive`, `acme_trino`). Trino policies use
catalog `awsdatacatalog` so they resolve against the Glue Data Catalog rather than being
filtered out. Coverage spans database, table, and column grants, wildcards, excludes, and
multi-principal policy items.

Principal names use the plain short-name style already established in the existing demo
(`alice_analyst`, `data_analysts`) and are identical to the Identity Center `userName` and
group `displayName` values the template creates, so no name normalisation is involved.

One policy deliberately grants to a Ranger **role**. `IdentityCenterPrincipalMapper`
always returns empty for roles, so this exercises the unmapped-principal path and gives
the demo a non-zero gap to talk about.

### `demo/idc/idc-demo-resources.yaml`

| Resource | Count | Notes |
|---|---|---|
| `AWS::SSO::Instance` | 1 | Account-level instance; `IdentityStoreId` is a readonly attribute consumed by every user and group via `!GetAtt` |
| `AWS::IdentityStore::Group` | 14 | `DisplayName` matches the Ranger group name |
| `AWS::IdentityStore::User` | 24 | `UserName` matches the Ranger user name |
| `AWS::IdentityStore::GroupMembership` | ~40 | |
| `AWS::Glue::Database` | 7 | |
| `AWS::Glue::Table` | ~45 | Columns include the sensitive ones policies reference (`email`, `ssn`, `salary`, `card_number`) |
| `AWS::S3::Bucket` | 1 | Supplies table `Location` values; no objects written |

Roughly 130 resources, well inside the 500-resource limit. Outputs the identity store ID,
the bucket name, and the database list.

Because the template owns the Identity Center instance, deleting the stack deletes the
instance and every user and group in it. That is acceptable in this demo account and is
the reason the instance is created by CloudFormation rather than referenced.

### `demo/idc/idc-demo-config.yaml`

Assessment configuration with `principalMapping.type: IDENTITY_CENTER`, the identity
store ID, account `427250191192`, and `rangerServices` entries for `acme_hive` and
`acme_trino` (the latter with `gdcCatalogName: awsdatacatalog`).

## Deployment and verification

1. `aws sso-admin delete-instance` on `ssoins-7223f48d0c53652d`.
2. `aws cloudformation create-stack` with the generated template.
3. Read the created identity store ID from the stack outputs into
   `idc-demo-config.yaml`.
4. Run the assessment against the fixture with `--aws-profile hocanint+ranger-test1`.

Success criteria: every user and group in the fixture resolves to an
`arn:aws:identitystore::427250191192:...` ARN; the only `unmappedPrincipal` count is the
single deliberate Ranger role; wildcard patterns expand against the real catalog instead
of being reported as `WILDCARD_PATTERN`.

## Deliberate omissions

No README. The user asked that documentation wait until the fixture is proven.
