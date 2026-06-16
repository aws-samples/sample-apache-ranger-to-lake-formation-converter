# Comprehensive Threat Model Report

**Generated**: 2026-06-16 12:30:02
**Current Phase**: 1 - Business Context Analysis
**Overall Completion**: 90.0%

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Business Context](#business-context)
3. [System Architecture](#system-architecture)
4. [Threat Actors](#threat-actors)
5. [Trust Boundaries](#trust-boundaries)
6. [Assets and Flows](#assets-and-flows)
7. [Threats](#threats)
8. [Mitigations](#mitigations)
9. [Assumptions](#assumptions)
10. [Phase Progress](#phase-progress)

## Executive Summary

ApacheRangerToLF is a Java-based security policy synchronization daemon that bridges Apache Ranger access-control policies to AWS Lake Formation permissions. It continuously polls the Ranger Admin REST API, translates Ranger policies into Cedar policy statements (via a Cedar intermediate representation), then applies the resulting permission grants and revocations to AWS Lake Formation and AWS Glue Data Catalog. The tool also optionally syncs S3 data-location access via AWS S3 Access Grants. It runs as a long-lived daemon in AWS-hosted data lake environments (EMR, Glue, Athena, Redshift Spectrum) and is security-critical middleware: it directly controls who can access what data in the lake. A compromise or misconfiguration of this tool can silently under-grant (data access denied for legitimate users) or over-grant (unauthorized principals gain data access), and since the tool holds AWS IAM credentials with lakeformation:GrantPermissions and lakeformation:RevokePermissions, an attacker who compromises the process gains the ability to rewrite all Lake Formation permissions in the AWS account.

### Key Statistics

- **Total Threats**: 20
- **Total Mitigations**: 18
- **Total Assumptions**: 20
- **System Components**: 10
- **Assets**: 15
- **Threat Actors**: 11

## Business Context

**Description**: ApacheRangerToLF is a Java-based security policy synchronization daemon that bridges Apache Ranger access-control policies to AWS Lake Formation permissions. It continuously polls the Ranger Admin REST API, translates Ranger policies into Cedar policy statements (via a Cedar intermediate representation), then applies the resulting permission grants and revocations to AWS Lake Formation and AWS Glue Data Catalog. The tool also optionally syncs S3 data-location access via AWS S3 Access Grants. It runs as a long-lived daemon in AWS-hosted data lake environments (EMR, Glue, Athena, Redshift Spectrum) and is security-critical middleware: it directly controls who can access what data in the lake. A compromise or misconfiguration of this tool can silently under-grant (data access denied for legitimate users) or over-grant (unauthorized principals gain data access), and since the tool holds AWS IAM credentials with lakeformation:GrantPermissions and lakeformation:RevokePermissions, an attacker who compromises the process gains the ability to rewrite all Lake Formation permissions in the AWS account.

### Business Features

- **Industry Sector**: Technology
- **Data Sensitivity**: Restricted
- **User Base Size**: Enterprise
- **Geographic Scope**: Multinational
- **Regulatory Requirements**: Multiple
- **System Criticality**: Mission-Critical
- **Financial Impact**: Severe
- **Authentication Requirement**: Federated
- **Deployment Environment**: Hybrid
- **Integration Complexity**: Highly Complex

## System Architecture

### Components

| ID | Name | Type | Service Provider | Description |
|---|---|---|---|---|
| C001 | ConversionServerMain (Sync Daemon) | Compute | On-Premise | Long-running daemon. Orchestrates full sync loop: fetches Ranger policies, converts to Cedar, computes diff, applies LF grants/revocations, runs reverse sync drift detection. Holds AWS credentials and Ranger Admin credentials in memory. |
| C002 | AssessmentMain (CLI Tool) | Compute | On-Premise | One-shot CLI assessment tool. Reads Ranger policies and generates gap/dry-run reports. Makes no AWS mutation calls. |
| C003 | Status HTTP Endpoint | Network | On-Premise | Unauthenticated plain-HTTP endpoint serving sync cycle timestamps and operational state. No TLS, no authentication. |
| C004 | Ranger Admin REST API | Other | Other | External policy source. GET /service/public/v2/api/service/{name}/policy. Default protocol is plain HTTP on port 6080. Auth: Basic or Kerberos. |
| C005 | AWS Lake Formation | Security | AWS | Receives BatchGrantPermissions and BatchRevokePermissions calls. Provides ListPermissions for reverse sync drift detection. |
| C006 | AWS Glue Data Catalog | Database | AWS | Metadata catalog. Used read-only by CatalogResolver to expand wildcard database/table patterns. |
| C007 | AWS STS | Security | AWS | AssumeRole endpoint used to obtain temporary high-privilege credentials for LF, Glue, CloudWatch, and S3 Access Grants operations. |
| C008 | AWS Identity Center / Identity Store | Security | AWS | Optional identity resolution. IdentityCenterPrincipalMapper resolves Ranger user/group names to IAM Identity Center IDs and IAM role ARNs. |
| C009 | AWS CloudWatch | Analytics | AWS | Receives operational metrics: sync cycle latency, grant/revoke counts, error rates. Write-only from the tool. |
| C010 | AWS S3 Access Grants | Storage | AWS | Receives S3 data-location permission grants for EMRFS policies. Uses an independent DefaultCredentialsProvider. |

### Connections

| ID | Source | Destination | Protocol | Port | Encrypted | Description |
|---|---|---|---|---|---|---|
| CN001 | C001 | C005 | HTTPS | 443 | Yes | BatchGrantPermissions and BatchRevokePermissions calls to Lake Formation. |
| CN002 | C001 | C006 | HTTPS | 443 | Yes | CatalogResolver read-only calls: GetDatabases, GetTables to expand wildcard resource patterns. |
| CN003 | C001 | C007 | HTTPS | 443 | Yes | AssumeRole call to STS to obtain temporary credentials for LF, Glue, CloudWatch, S3 operations. |
| CN004 | C001 | C008 | HTTPS | 443 | Yes | Optional: IdentityCenterPrincipalMapper resolves Ranger user/group names to IAM ARNs via Identity Store API. |
| CN005 | C001 | C009 | HTTPS | 443 | Yes | MetricsEmitter writes sync cycle metrics (latency, grant/revoke counts, errors) to CloudWatch. |
| CN006 | C001 | C010 | HTTPS | 443 | Yes | S3AccessGrantsClient applies EMRFS data-location grants to S3 Access Grants service. |
| CN007 | C001 | C003 | HTTP | N/A | No | Sync daemon hosts the status endpoint. Responds to inbound HTTP GETs with sync timestamps and state. |
| CN008 | C001 | C004 | HTTP | 6080 | No | Sync daemon fetches Ranger policies via REST GET. Default is plain HTTP (unencrypted). HTTPS optional with mTLS via SSL XML config. |
| CN009 | C002 | C004 | HTTP | 6080 | No | Assessment CLI fetches Ranger policies for read-only gap analysis. No mutation of AWS state. |
| CN010 | C001 | C003 | HTTP | 8080 | No | Sync daemon hosts the unauthenticated status endpoint. Responds to inbound HTTP GETs with sync timestamps and state string. |

### Data Stores

| ID | Name | Type | Classification | Encrypted at Rest | Description |
|---|---|---|---|---|---|
| D001 | YAML Config File | File System | Restricted | No | Stores Ranger Admin URL, username, password (plaintext), optional static AWS credentials, role ARN, service names, checkpoint path, and status endpoint port. The primary secret store. |
| D002 | Checkpoint File (Cedar policy set) | File System | Confidential | No | JSON file containing full Cedar policy text and per-service version map. Contains all resolved principal ARNs and resource permission mappings. Protected only by filesystem ACLs. |
| D003 | Dead-Letter Log (JSONL) | File System | Confidential | No | Append-only JSONL file of failed BatchGrant/BatchRevoke operations. Each record contains principal ARNs, LF resource identifiers, and permission sets. |
| D004 | Principal Mapping File (JSON) | File System | Restricted | No | Static JSON mapping Ranger user/group names to IAM Role ARNs. A complete inventory of identity-to-permission mapping. Loaded at startup; no runtime change detection. |
| D005 | Gap Report / Dry-Run Output | File System | Internal | No | Output files from AssessmentMain. Contains unconvertible policy details, resource paths, and gap reasons. |
| D006 | Ranger Admin Policy Database | Relational | Restricted | Yes | Upstream Ranger Admin database. Out of scope for this tool but the authoritative source of all permission data fetched. |
| D007 | AWS Lake Formation Permission Store | Other | Restricted | Yes | AWS-managed store of all Lake Formation grants. Directly mutated by this tool via BatchGrant/BatchRevoke. |

## Threat Actors

### Insider

- **Type**: ThreatActorType.INSIDER
- **Capability Level**: CapabilityLevel.MEDIUM
- **Motivations**: Financial, Revenge
- **Resources**: ResourceLevel.LIMITED
- **Relevant**: Yes
- **Priority**: 2/10
- **Description**: An employee or contractor with legitimate access to the system

### External Attacker

- **Type**: ThreatActorType.EXTERNAL
- **Capability Level**: CapabilityLevel.MEDIUM
- **Motivations**: Financial
- **Resources**: ResourceLevel.MODERATE
- **Relevant**: Yes
- **Priority**: 4/10
- **Description**: An external individual or group attempting to gain unauthorized access

### Nation-state Actor

- **Type**: ThreatActorType.NATION_STATE
- **Capability Level**: CapabilityLevel.HIGH
- **Motivations**: Espionage, Political
- **Resources**: ResourceLevel.EXTENSIVE
- **Relevant**: Yes
- **Priority**: 7/10
- **Description**: A government-sponsored group with advanced capabilities

### Hacktivist

- **Type**: ThreatActorType.HACKTIVIST
- **Capability Level**: CapabilityLevel.MEDIUM
- **Motivations**: Ideology, Political
- **Resources**: ResourceLevel.MODERATE
- **Relevant**: No
- **Priority**: 6/10
- **Description**: An individual or group motivated by ideological or political beliefs

### Organized Crime

- **Type**: ThreatActorType.ORGANIZED_CRIME
- **Capability Level**: CapabilityLevel.HIGH
- **Motivations**: Financial
- **Resources**: ResourceLevel.EXTENSIVE
- **Relevant**: Yes
- **Priority**: 8/10
- **Description**: A criminal organization with significant resources

### Competitor

- **Type**: ThreatActorType.COMPETITOR
- **Capability Level**: CapabilityLevel.MEDIUM
- **Motivations**: Financial, Espionage
- **Resources**: ResourceLevel.MODERATE
- **Relevant**: Yes
- **Priority**: 9/10
- **Description**: A business competitor seeking competitive advantage

### Script Kiddie

- **Type**: ThreatActorType.SCRIPT_KIDDIE
- **Capability Level**: CapabilityLevel.LOW
- **Motivations**: Curiosity, Reputation
- **Resources**: ResourceLevel.LIMITED
- **Relevant**: No
- **Priority**: 9/10
- **Description**: An inexperienced attacker using pre-made tools

### Disgruntled Employee

- **Type**: ThreatActorType.DISGRUNTLED_EMPLOYEE
- **Capability Level**: CapabilityLevel.MEDIUM
- **Motivations**: Revenge
- **Resources**: ResourceLevel.LIMITED
- **Relevant**: Yes
- **Priority**: 6/10
- **Description**: A current or former employee with a grievance

### Privileged User

- **Type**: ThreatActorType.PRIVILEGED_USER
- **Capability Level**: CapabilityLevel.HIGH
- **Motivations**: Financial, Accidental
- **Resources**: ResourceLevel.MODERATE
- **Relevant**: Yes
- **Priority**: 3/10
- **Description**: A user with elevated privileges who may abuse them or make mistakes

### Third Party

- **Type**: ThreatActorType.THIRD_PARTY
- **Capability Level**: CapabilityLevel.MEDIUM
- **Motivations**: Financial, Accidental
- **Resources**: ResourceLevel.MODERATE
- **Relevant**: Yes
- **Priority**: 5/10
- **Description**: A vendor, partner, or service provider with access to the system

### Compromised Upstream System (Ranger Admin)

- **Type**: ThreatActorType.EXTERNAL
- **Capability Level**: CapabilityLevel.HIGH
- **Motivations**: Espionage, Financial
- **Resources**: ResourceLevel.EXTENSIVE
- **Relevant**: Yes
- **Priority**: 1/10
- **Description**: An attacker who has gained control of the Ranger Admin server and injects malicious policies that the sync daemon faithfully translates to Lake Formation grants. Since the tool trusts Ranger Admin as the sole authority, policy injection in Ranger directly results in unauthorized LF permission grants across the entire data lake.

## Trust Boundaries

### Trust Zones

#### Internet

- **Trust Level**: TrustLevel.UNTRUSTED
- **Description**: The public internet, considered untrusted

#### DMZ

- **Trust Level**: TrustLevel.LOW
- **Description**: Demilitarized zone for public-facing services

#### Application

- **Trust Level**: TrustLevel.MEDIUM
- **Description**: Zone containing application servers and services

#### Data

- **Trust Level**: TrustLevel.HIGH
- **Description**: Zone containing databases and data storage

#### Admin

- **Trust Level**: TrustLevel.FULL
- **Description**: Administrative zone with highest privileges

#### On-Premise Compute Zone

- **Trust Level**: TrustLevel.HIGH
- **Description**: The host where the sync daemon (ConversionServerMain) and CLI (AssessmentMain) run. Includes JVM process memory holding credentials and policy data. Trust is high because this is operator-controlled infrastructure, but compromise of this zone yields full credential access.

#### On-Premise Filesystem Zone

- **Trust Level**: TrustLevel.HIGH
- **Description**: Local filesystem paths holding the YAML config (credentials), checkpoint file (Cedar policy set), dead-letter log, principal mapping file, and gap reports. Protected only by OS-level ACLs.

#### Internal Network Zone

- **Trust Level**: TrustLevel.MEDIUM
- **Description**: The network segment connecting the sync daemon to Ranger Admin. Default configuration uses plain HTTP, making this effectively untrusted for credential and policy data in transit.

#### Ranger Admin Zone

- **Trust Level**: TrustLevel.MEDIUM
- **Description**: The Apache Ranger Admin server. Trusted as the authoritative policy source but not directly controlled by this tool's operators. Compromise here propagates directly to Lake Formation permissions.

#### AWS Cloud Zone

- **Trust Level**: TrustLevel.HIGH
- **Description**: AWS API endpoints for Lake Formation, Glue Data Catalog, STS, IAM Identity Center, CloudWatch, and S3 Access Grants. All communications use HTTPS/TLS. Trust is high due to AWS-managed infrastructure and TLS, but the IAM role held here is extremely high-privilege.

#### Unauthenticated Public Zone

- **Trust Level**: TrustLevel.UNTRUSTED
- **Description**: Any host or process that can reach the status HTTP endpoint port. No authentication, no TLS. Represents the attack surface of the open status endpoint.

### Trust Boundaries

#### Internet Boundary

- **Type**: BoundaryType.NETWORK
- **Controls**: Web Application Firewall, DDoS Protection, TLS Encryption
- **Description**: Boundary between the internet and internal systems

#### DMZ Boundary

- **Type**: BoundaryType.NETWORK
- **Controls**: Network Firewall, Intrusion Detection System, API Gateway
- **Description**: Boundary between public-facing services and internal applications

#### Data Boundary

- **Type**: BoundaryType.NETWORK
- **Controls**: Database Firewall, Encryption, Access Control Lists
- **Description**: Boundary protecting data storage systems

#### Admin Boundary

- **Type**: BoundaryType.NETWORK
- **Controls**: Privileged Access Management, Multi-Factor Authentication, Audit Logging
- **Description**: Boundary for administrative access

#### Filesystem Access Control Boundary

- **Type**: BoundaryType.PROCESS
- **Controls**: OS filesystem ACLs, File ownership restrictions
- **Description**: Boundary between the JVM process and the local filesystem. Config (credentials), checkpoint (Cedar policy set), dead-letter log, and principal mapping are all protected solely by OS-level file permissions. No encryption at rest.

#### Ranger Admin Network Boundary

- **Type**: BoundaryType.NETWORK
- **Controls**: HTTP Basic Auth (Base64), Optional HTTPS with mTLS, Optional Kerberos
- **Description**: Network boundary between the sync daemon and Ranger Admin. Default is plain HTTP — no TLS. Credentials and full policy data transit unencrypted by default. Highest-risk network boundary in the architecture.

#### AWS API Boundary

- **Type**: BoundaryType.NETWORK
- **Controls**: HTTPS/TLS 1.2+, IAM Role authentication via STS, AWS SDK v2 request signing (SigV4), CloudTrail audit logging
- **Description**: Boundary between the on-premise JVM and AWS APIs (LF, Glue, STS, CloudWatch, S3 Access Grants). All traffic encrypted. IAM role used is high-privilege and broad-scope.

#### Status Endpoint Boundary

- **Type**: BoundaryType.NETWORK
- **Description**: Boundary at the unauthenticated HTTP status endpoint. No TLS, no authentication, no authorization. Any host with network access to the configured port can query the endpoint. No controls are currently in place.

## Assets and Flows

### Assets

| ID | Name | Type | Classification | Sensitivity | Criticality | Owner |
|---|---|---|---|---|---|---|
| A001 | User Credentials | AssetType.CREDENTIAL | AssetClassification.CONFIDENTIAL | 5 | 5 | N/A |
| A002 | Personal Identifiable Information | AssetType.DATA | AssetClassification.CONFIDENTIAL | 4 | 4 | N/A |
| A003 | Session Token | AssetType.TOKEN | AssetClassification.CONFIDENTIAL | 5 | 5 | N/A |
| A004 | Configuration Data | AssetType.CONFIG | AssetClassification.INTERNAL | 3 | 4 | N/A |
| A005 | Encryption Keys | AssetType.KEY | AssetClassification.RESTRICTED | 5 | 5 | N/A |
| A006 | Public Content | AssetType.DATA | AssetClassification.PUBLIC | 1 | 2 | N/A |
| A007 | Audit Logs | AssetType.DATA | AssetClassification.INTERNAL | 3 | 4 | N/A |
| A008 | Ranger Admin Credentials (username/password) | AssetType.CREDENTIAL | AssetClassification.RESTRICTED | 5 | 5 | Operations Team |
| A009 | AWS IAM Credentials (role ARN / static keys) | AssetType.CREDENTIAL | AssetClassification.RESTRICTED | 5 | 5 | Operations Team |
| A010 | Ranger Access-Control Policies | AssetType.DATA | AssetClassification.CONFIDENTIAL | 4 | 5 | Data Governance Team |
| A011 | Cedar Policy Set (Checkpoint File) | AssetType.DATA | AssetClassification.CONFIDENTIAL | 4 | 5 | Operations Team |
| A012 | Principal Mapping (Ranger identity to IAM ARN) | AssetType.DATA | AssetClassification.RESTRICTED | 5 | 4 | Identity Team |
| A013 | Dead-Letter Log Records | AssetType.DATA | AssetClassification.CONFIDENTIAL | 3 | 3 | Operations Team |
| A014 | Lake Formation Permission Grants | AssetType.DATA | AssetClassification.RESTRICTED | 5 | 5 | Data Governance Team |
| A015 | TLS Keystore and Truststore Credentials | AssetType.CREDENTIAL | AssetClassification.RESTRICTED | 3 | 3 | Operations Team |

### Asset Flows

| ID | Asset | Source | Destination | Protocol | Encrypted | Risk Level |
|---|---|---|---|---|---|---|
| F001 | User Credentials | C001 | C002 | HTTPS | Yes | 4 |
| F002 | Session Token | C002 | C001 | HTTPS | Yes | 3 |
| F003 | Personal Identifiable Information | C003 | C004 | TLS | Yes | 3 |
| F004 | Audit Logs | C003 | C005 | TLS | Yes | 2 |

## Threats

### Identified Threats

#### T1: Network attacker with access to internal segment

**Statement**: A Network attacker with access to internal segment Ranger Admin configured with plain HTTP (default) can Intercept HTTP traffic between sync daemon and Ranger Admin to steal Basic Auth credentials, which leads to Full Ranger Admin access; attacker can read/modify all Ranger policies

- **Prerequisites**: Ranger Admin configured with plain HTTP (default)
- **Action**: Intercept HTTP traffic between sync daemon and Ranger Admin to steal Basic Auth credentials
- **Impact**: Full Ranger Admin access; attacker can read/modify all Ranger policies
- **Impacted Assets**: A008
- **Tags**: STRIDE-S, Network, Credentials, NotMitigated

#### T2: Network attacker with MitM position

**Statement**: A Network attacker with MitM position Plain HTTP to Ranger Admin; no certificate pinning can Impersonate Ranger Admin server and serve fabricated policies to the sync daemon, which leads to Sync daemon grants unauthorized principals LF permissions based on injected policies

- **Prerequisites**: Plain HTTP to Ranger Admin; no certificate pinning
- **Action**: Impersonate Ranger Admin server and serve fabricated policies to the sync daemon
- **Impact**: Sync daemon grants unauthorized principals LF permissions based on injected policies
- **Impacted Assets**: A010, A014
- **Tags**: STRIDE-S, Network, PolicyInjection, NotMitigated

#### T3: Attacker with filesystem read access to host

**Statement**: A Attacker with filesystem read access to host Config file readable beyond the daemon process user can Read YAML config to obtain Ranger Admin password and/or AWS static credentials, which leads to Full Ranger Admin and/or AWS Lake Formation privilege escalation

- **Prerequisites**: Config file readable beyond the daemon process user
- **Action**: Read YAML config to obtain Ranger Admin password and/or AWS static credentials
- **Impact**: Full Ranger Admin and/or AWS Lake Formation privilege escalation
- **Impacted Assets**: A008, A009
- **Tags**: STRIDE-I, Filesystem, Credentials, NotMitigated

#### T4: Attacker with filesystem read access to host

**Statement**: A Attacker with filesystem read access to host Checkpoint file readable beyond the daemon process user can Read checkpoint file to obtain all principal ARNs, resource paths, and permission mappings, which leads to Complete data-lake access-control topology disclosed; enables targeted privilege escalation

- **Prerequisites**: Checkpoint file readable beyond the daemon process user
- **Action**: Read checkpoint file to obtain all principal ARNs, resource paths, and permission mappings
- **Impact**: Complete data-lake access-control topology disclosed; enables targeted privilege escalation
- **Impacted Assets**: A011
- **Tags**: STRIDE-I, Filesystem, PolicyData, NotMitigated

#### T5: Attacker with filesystem write access to host

**Statement**: A Attacker with filesystem write access to host Checkpoint file writable beyond the daemon process user can Inject crafted Cedar policy statements into checkpoint file before next sync cycle, which leads to Sync daemon applies attacker-controlled LF grants; unauthorized principals gain data-lake access

- **Prerequisites**: Checkpoint file writable beyond the daemon process user
- **Action**: Inject crafted Cedar policy statements into checkpoint file before next sync cycle
- **Impact**: Sync daemon applies attacker-controlled LF grants; unauthorized principals gain data-lake access
- **Impacted Assets**: A011, A014
- **Tags**: STRIDE-T, Filesystem, PolicyInjection, NotMitigated

#### T6: Attacker with filesystem write access to host

**Statement**: A Attacker with filesystem write access to host Principal mapping file writable beyond daemon user can Modify principal-mapping.json to redirect Ranger identities to attacker-controlled IAM role ARNs, which leads to All Ranger-mapped LF grants land on attacker roles; full unauthorized data-lake access

- **Prerequisites**: Principal mapping file writable beyond daemon user
- **Action**: Modify principal-mapping.json to redirect Ranger identities to attacker-controlled IAM role ARNs
- **Impact**: All Ranger-mapped LF grants land on attacker roles; full unauthorized data-lake access
- **Impacted Assets**: A012, A014
- **Tags**: STRIDE-T, Filesystem, IdentityMapping, NotMitigated

#### T7: Compromised Ranger Admin server or insider with Ranger Admin access

**Statement**: A Compromised Ranger Admin server or insider with Ranger Admin access Attacker can create or modify Ranger policies can Inject malicious Ranger policies granting attacker IAM roles access to sensitive tables, which leads to Sync daemon faithfully translates injected policies into LF grants; data exfiltration enabled

- **Prerequisites**: Attacker can create or modify Ranger policies
- **Action**: Inject malicious Ranger policies granting attacker IAM roles access to sensitive tables
- **Impact**: Sync daemon faithfully translates injected policies into LF grants; data exfiltration enabled
- **Impacted Assets**: A010, A014
- **Tags**: STRIDE-E, PolicyInjection, UpstreamCompromise

#### T8: Insider or attacker with access to running JVM

**Statement**: A Insider or attacker with access to running JVM Ability to attach debugger or heap dump JVM process can Dump JVM heap to extract in-memory Ranger credentials and AWS temporary credentials, which leads to Full Ranger Admin and AWS IAM access via extracted credentials

- **Prerequisites**: Ability to attach debugger or heap dump JVM process
- **Action**: Dump JVM heap to extract in-memory Ranger credentials and AWS temporary credentials
- **Impact**: Full Ranger Admin and AWS IAM access via extracted credentials
- **Impacted Assets**: A008, A009
- **Tags**: STRIDE-I, MemoryDump, Credentials

#### T9: Any host with network access to status endpoint port

**Statement**: A Any host with network access to status endpoint port No authentication or network-level firewall on status endpoint port can Query unauthenticated HTTP status endpoint to determine sync state and timing, which leads to Operational intelligence for timing attacks; confirm whether security sync is running

- **Prerequisites**: No authentication or network-level firewall on status endpoint port
- **Action**: Query unauthenticated HTTP status endpoint to determine sync state and timing
- **Impact**: Operational intelligence for timing attacks; confirm whether security sync is running
- **Tags**: STRIDE-I, Network, UnauthEndpoint, NotMitigated

#### T10: Attacker with network access to status endpoint

**Statement**: A Attacker with network access to status endpoint No rate limiting or authentication on status endpoint can Flood status endpoint with HTTP requests to exhaust JVM thread pool, which leads to Sync daemon becomes unresponsive; sync cycles delayed or stopped; LF permissions stale

- **Prerequisites**: No rate limiting or authentication on status endpoint
- **Action**: Flood status endpoint with HTTP requests to exhaust JVM thread pool
- **Impact**: Sync daemon becomes unresponsive; sync cycles delayed or stopped; LF permissions stale
- **Impacted Assets**: A014
- **Tags**: STRIDE-D, Network, DoS, NotMitigated

#### T11: Attacker or misconfigured operator

**Statement**: A Attacker or misconfigured operator Ability to modify Ranger policies to create conflicting TABLE and TWC grants for same principal can Trigger TABLE/TWC conflict handler to suppress legitimate TABLE grants, which leads to Known bug: TABLE grant never re-applied after conflict resolution; persistent LF under-grant

- **Prerequisites**: Ability to modify Ranger policies to create conflicting TABLE and TWC grants for same principal
- **Action**: Trigger TABLE/TWC conflict handler to suppress legitimate TABLE grants
- **Impact**: Known bug: TABLE grant never re-applied after conflict resolution; persistent LF under-grant
- **Impacted Assets**: A014
- **Tags**: STRIDE-D, ConflictBug, UnderGrant, KnownBug

#### T12: Attacker controlling network path to AWS APIs

**Statement**: A Attacker controlling network path to AWS APIs Network path between JVM and AWS APIs is compromised can Block or delay BatchGrant/BatchRevoke calls to freeze LF permissions in stale state, which leads to LF permissions drift from intended state; access denied for legitimate users or stale grants persist

- **Prerequisites**: Network path between JVM and AWS APIs is compromised
- **Action**: Block or delay BatchGrant/BatchRevoke calls to freeze LF permissions in stale state
- **Impact**: LF permissions drift from intended state; access denied for legitimate users or stale grants persist
- **Impacted Assets**: A014
- **Tags**: STRIDE-D, Network, APIBlocking

#### T13: Attacker who compromises the sync daemon process

**Statement**: A Attacker who compromises the sync daemon process Code execution in JVM; high-privilege IAM role available can Use daemon IAM credentials to call LF BatchGrantPermissions for arbitrary principals on any resource, which leads to Attacker grants any IAM principal full access to any table in the data lake

- **Prerequisites**: Code execution in JVM; high-privilege IAM role available
- **Action**: Use daemon IAM credentials to call LF BatchGrantPermissions for arbitrary principals on any resource
- **Impact**: Attacker grants any IAM principal full access to any table in the data lake
- **Impacted Assets**: A009, A014
- **Tags**: STRIDE-E, CredentialAbuse, PrivilegeEscalation

#### T14: Sync daemon

**Statement**: A Sync daemon Cedar forbid statements generated from Ranger deny policies can Apply Ranger deny policies as Cedar forbid—forbid statements dropped silently in LF sync path, which leads to Ranger deny policies not enforced at LF layer; principals retain access that should be denied

- **Prerequisites**: Cedar forbid statements generated from Ranger deny policies
- **Action**: Apply Ranger deny policies as Cedar forbid—forbid statements dropped silently in LF sync path
- **Impact**: Ranger deny policies not enforced at LF layer; principals retain access that should be denied
- **Impacted Assets**: A014
- **Tags**: STRIDE-E, SemanticGap, DenyNotEnforced, PartiallyMitigated

#### T15: Operator or attacker with config file access

**Statement**: A Operator or attacker with config file access Default TLS keystore/truststore password 'changeit' not rotated can Use default keystore password to extract private key for mTLS client cert, which leads to Attacker can impersonate the sync daemon to Ranger Admin or bypass server cert validation

- **Prerequisites**: Default TLS keystore/truststore password 'changeit' not rotated
- **Action**: Use default keystore password to extract private key for mTLS client cert
- **Impact**: Attacker can impersonate the sync daemon to Ranger Admin or bypass server cert validation
- **Impacted Assets**: A015
- **Tags**: STRIDE-S, DefaultCredentials, TLS, NotMitigated

#### T16: Sync daemon or operator

**Statement**: A Sync daemon or operator Dead-letter log accessible to broader set of users than the daemon process can Read dead-letter JSONL to enumerate all LF resource identifiers and principal ARNs from failed ops, which leads to Full inventory of data-lake resources and identity mappings disclosed from error logs

- **Prerequisites**: Dead-letter log accessible to broader set of users than the daemon process
- **Action**: Read dead-letter JSONL to enumerate all LF resource identifiers and principal ARNs from failed ops
- **Impact**: Full inventory of data-lake resources and identity mappings disclosed from error logs
- **Impacted Assets**: A013
- **Tags**: STRIDE-I, Filesystem, LogLeakage

#### T17: Attacker with write access to YAML config

**Statement**: A Attacker with write access to YAML config Config file writable; attacker can restart daemon can Modify YAML config to point to attacker-controlled Ranger Admin URL, which leads to Daemon fetches attacker-crafted policies; arbitrary LF grants applied in target AWS account

- **Prerequisites**: Config file writable; attacker can restart daemon
- **Action**: Modify YAML config to point to attacker-controlled Ranger Admin URL
- **Impact**: Daemon fetches attacker-crafted policies; arbitrary LF grants applied in target AWS account
- **Impacted Assets**: A010, A014
- **Tags**: STRIDE-T, ConfigTampering, PolicyInjection

#### T18: Sync daemon (accidental)

**Statement**: A Sync daemon (accidental) Wildcard * policies present in Ranger; reverse sync disabled (default) can Wildcard policies not translated to LF grants due to known bug; no error raised, which leads to Legitimate users denied access silently; no alerting mechanism surfaces the gap

- **Prerequisites**: Wildcard * policies present in Ranger; reverse sync disabled (default)
- **Action**: Wildcard policies not translated to LF grants due to known bug; no error raised
- **Impact**: Legitimate users denied access silently; no alerting mechanism surfaces the gap
- **Impacted Assets**: A014
- **Tags**: STRIDE-D, WildcardBug, UnderGrant, PartiallyMitigated

#### T19: Attacker with Ranger Admin access or network MitM

**Statement**: A Attacker with Ranger Admin access or network MitM No audit trail correlating Ranger policy changes to LF permission changes can Inject or modify Ranger policy then remove evidence in Ranger audit log, which leads to Unauthorized LF grant exists with no traceable origin; repudiation of policy injection

- **Prerequisites**: No audit trail correlating Ranger policy changes to LF permission changes
- **Action**: Inject or modify Ranger policy then remove evidence in Ranger audit log
- **Impact**: Unauthorized LF grant exists with no traceable origin; repudiation of policy injection
- **Impacted Assets**: A010, A014
- **Tags**: STRIDE-R, AuditGap, PolicyInjection, PartiallyMitigated

#### T20: Third-party vendor or compromised dependency

**Statement**: A Third-party vendor or compromised dependency Malicious code in Java dependency (supply chain attack) can Backdoored library exfiltrates in-memory credentials or injects policy modifications, which leads to Silent credential theft or policy manipulation with no observable anomaly

- **Prerequisites**: Malicious code in Java dependency (supply chain attack)
- **Action**: Backdoored library exfiltrates in-memory credentials or injects policy modifications
- **Impact**: Silent credential theft or policy manipulation with no observable anomaly
- **Impacted Assets**: A008, A009, A010
- **Tags**: STRIDE-I, SupplyChain, Credentials

## Mitigations

### Identified Mitigations

#### M1: Enforce HTTPS with certificate validation for all Ranger Admin communication

**Addresses Threats**: T1, T2, T17

#### M2: Restrict filesystem ACLs on config, checkpoint, principal-mapping, and dead-letter files

**Addresses Threats**: T3, T4, T5, T6, T16

#### M3: Integrate AWS Secrets Manager (or HashiCorp Vault) for Ranger Admin password and static AWS credentials

**Addresses Threats**: T3, T8, T17

#### M4: Add authentication and TLS to the status HTTP endpoint

**Addresses Threats**: T9, T10

#### M5: Apply least-privilege IAM policy scoped to specific LF databases and tables

**Addresses Threats**: T13, T7

#### M6: Fix TABLE/TWC conflict handler to re-apply TABLE grant after conflict resolution

**Addresses Threats**: T11, T18

#### M7: Validate Ranger Admin server identity using certificate pinning or strict CA trust

**Addresses Threats**: T2, T15

#### M8: Add HMAC integrity check on checkpoint file at write and read

**Addresses Threats**: T5, T6

#### M9: Rotate default TLS keystore and truststore passwords from 'changeit'

**Addresses Threats**: T15

#### M10: Enable and enforce ReverseSyncService by default to detect and correct LF drift

**Addresses Threats**: T14, T12, T11

#### M12: Add dependency scanning (OWASP Dependency-Check or Snyk) and publish SBOM

**Addresses Threats**: T20

#### M13: Apply log rotation and ACL to dead-letter log; cap maximum file size

**Addresses Threats**: T16

#### M14: Add ConfigValidator rejection of http:// Ranger Admin URLs with an actionable error

**Addresses Threats**: T1, T2

### In Progress Mitigations

#### M11: Implement structured audit log correlating Ranger policy versions to LF API calls

**Addresses Threats**: T19, T7

#### M15: Document Cedar forbid semantic gap prominently; add operator warning at startup

**Addresses Threats**: T14

#### M16: Fix wildcard policy translation to correctly expand and apply wildcard Ranger grants to LF

**Addresses Threats**: T18

#### M17: Run sync daemon in a least-privilege container with no network access beyond required endpoints

**Addresses Threats**: T13, T12

#### M18: Implement Ranger policy allowlist/schema validation before Cedar conversion

**Addresses Threats**: T7, T19

## Assumptions

### A001: Trust

**Description**: The Ranger Admin server is a trusted, separately hardened system. Its policies are the authoritative source of truth and are not manipulated before reaching this tool.

- **Impact**: If Ranger Admin is compromised, all downstream LF permissions can be poisoned via policy injection.
- **Rationale**: The tool performs no integrity verification on policies fetched from Ranger Admin beyond schema parsing.

### A002: Authorization

**Description**: The tool runs with a high-privilege IAM role that has lakeformation:GrantPermissions, lakeformation:RevokePermissions, lakeformation:ListPermissions, glue:GetDatabases, glue:GetTables, and identitystore:* rights. This role is not scoped to specific resources.

- **Impact**: Compromise of the process or its credentials gives an attacker full Lake Formation permission management in the AWS account.
- **Rationale**: The API calls require account-level permissions; Lake Formation does not support resource-level IAM conditions on grant/revoke.

### A003: Network

**Description**: The default configuration uses HTTP (not HTTPS) to communicate with Ranger Admin. HTTPS is optional and operator-configured.

- **Impact**: Ranger Admin credentials and full policy data may transit the network in plaintext, enabling credential theft and MitM policy injection.
- **Rationale**: The ConfigValidator accepts http:// URLs without warning; the deploy example config uses http://.

### A004: Secrets Management

**Description**: The Ranger Admin password and optionally AWS static credentials are stored in plaintext in the YAML config file on disk.

- **Impact**: Any process or user with read access to the config file can obtain credentials to both Ranger Admin and AWS.
- **Rationale**: No secrets manager integration (Secrets Manager, Vault, etc.) is present; env-var injection is recommended but not enforced.

### A005: Authentication

**Description**: The unauthenticated HTTP status endpoint (StatusEndpoint) exposes the tool's operational state to anyone with network access to the listening port.

- **Impact**: Limited information disclosure (timestamps, state string), but the open port expands the attack surface.
- **Rationale**: No auth, no TLS, no access control on the status endpoint.

### A006: Semantics

**Description**: Cedar forbid statements (deny policies from Ranger) are not applied as LF revocations. They influence only in-memory Cedar evaluation and are silently dropped in the LF sync path.

- **Impact**: Ranger deny policies intended to restrict access are not enforced at the LF layer, resulting in possible over-grants.
- **Rationale**: Lake Formation uses a grant-only model; there is no LF equivalent to a blanket deny.

### A007: Data Storage

**Description**: The checkpoint file and dead-letter log are stored on the local filesystem. File system ACLs are the sole protection for the Cedar policy set (containing all principal ARNs and resource permissions) and failed-operation records.

- **Impact**: Unauthorized read of checkpoint reveals full access-control topology; unauthorized write could inject arbitrary Cedar policy to manipulate future sync cycles.
- **Rationale**: No encryption-at-rest for local files; no integrity checking on checkpoint load.

### A008: Identity

**Description**: The principal-mapping file (principal-mapping.json or IdentityCenter) maps Ranger user/group names to IAM Role ARNs. This mapping is static-file or API-driven with no change detection during operation.

- **Impact**: A tampered mapping file could redirect permissions to attacker-controlled IAM roles.
- **Rationale**: The mapping file is loaded at startup; changes require restart unless IdentityCenter API is used.

### A009: Code Analysis

**Description**: Code analysis confirmed: ConfigValidator accepts both http:// and https:// Ranger Admin URLs. No warning or rejection for plaintext http://. Default config and integration tests use http://.

- **Impact**: Credential theft and policy injection via network MitM remain unmitigated at the code level.
- **Rationale**: Verified in ConfigValidator.java:118-123

### A010: Code Analysis

**Description**: Code analysis confirmed: Credentials (Ranger password, AWS secret key) are masked in logs via ConfigLoader.mask() and logConfig(). This is the only credential protection implemented in code.

- **Impact**: Log-based credential disclosure is mitigated. File-based and network-based disclosure remain open.
- **Rationale**: Verified in ConfigLoader.java:318, logConfig() lines 104-128

### A011: Code Analysis

**Description**: Code analysis confirmed: No filesystem ACL enforcement exists. Config YAML, checkpoint JSON, principal-mapping JSON, and dead-letter JSONL are created with OS-default umask permissions.

- **Impact**: Any process running as the same OS user can read credentials and policy data from all local files.
- **Rationale**: Verified by absence of Files.setPosixFilePermissions or chmod in all main-path Java sources

### A012: Code Analysis

**Description**: Code analysis confirmed: CheckpointStore has no integrity check (no HMAC, hash, or CRC). Checkpoint is loaded and deserialized from JSON directly. An attacker with write access to the checkpoint file can inject arbitrary Cedar policy.

- **Impact**: Checkpoint tampering threat is fully unmitigated in code.
- **Rationale**: Verified in CheckpointStore.java load() method

### A013: Code Analysis

**Description**: Code analysis confirmed: StatusEndpoint uses com.sun.net.httpserver.HttpServer (plain HTTP), no authentication, no TLS, no rate limiting, unbounded thread pool. Fully open to any host.

- **Impact**: Information disclosure and DoS via status endpoint are fully unmitigated in code.
- **Rationale**: Verified in StatusEndpoint.java lines 29-41

### A014: Code Analysis

**Description**: Code analysis confirmed: ReverseSyncService is disabled by default (ReverseSyncConfig.enabled defaults to false, server-config.yaml sets enabled: false). Drift detection does not run unless explicitly enabled.

- **Impact**: LF permission drift from intended state will not be auto-corrected unless operator explicitly enables reverse sync.
- **Rationale**: Verified in ReverseSyncConfig constructor and server-config.yaml:142

### A015: Code Analysis

**Description**: Code analysis confirmed: CatalogResolver expands wildcards against Glue API. On success, wildcard grants are fully expanded. On AWS exception, returns emptyList() with error log — effective silent drop of that grant.

- **Impact**: Wildcard expansion is implemented but has a silent failure mode; Glue API errors cause under-grants without raising a hard error.
- **Rationale**: Verified in CatalogResolver.java expandDatabases/expandTables methods

### A016: Code Analysis

**Description**: Code analysis confirmed: No AWS Secrets Manager, HashiCorp Vault, or any external secret store integration exists. All credentials come from YAML config or environment variables only.

- **Impact**: Plaintext credential storage in config files remains the only option; no rotation or audit trail on credential access.
- **Rationale**: Full grep for SecretsManager, secretsmanager, Vault returned zero results in src/main/java/

### A017: Residual Risk

**Description**: RESIDUAL RISK: The Cedar forbid semantic gap (Ranger deny policies not enforced at LF) is an architectural constraint of LF's grant-only model. This risk is ACCEPTED with mitigation: startup warning logged; operators must understand LF has no deny model.

- **Impact**: Medium: Ranger deny policies do not restrict LF access. Operators relying on Ranger denies for security enforcement will have a false sense of security.
- **Rationale**: Lake Formation does not support explicit deny grants; the gap is fundamental to the LF service model.

### A018: Residual Risk

**Description**: RESIDUAL RISK: The TABLE/TWC conflict under-grant bug (Bug #3) is OPEN as of 2026-06-16. The displaced TABLE grant is never re-applied after conflict resolution. Risk is ACCEPTED pending fix.

- **Impact**: High: Legitimate data access may be silently denied for principals with both TABLE and TWC Ranger policies.
- **Rationale**: Tracked in bug_table_twc_coexistence.md. Fix is planned but not implemented.

### A019: Residual Risk

**Description**: RESIDUAL RISK: The unauthenticated status endpoint represents LOW residual risk if network access to the daemon host is restricted. Risk is CONDITIONALLY ACCEPTED if the status port is blocked at the network perimeter.

- **Impact**: Low if network-segmented. High if daemon is reachable from untrusted networks.
- **Rationale**: Endpoint exposes only timestamps and state string. Requires operator to enforce network-level access controls.

### A020: Residual Risk

**Description**: RESIDUAL RISK: Plaintext Ranger Admin communication (HTTP default) is the HIGHEST unmitigated risk. Until HTTPS is enforced in ConfigValidator, every deployment that uses the default config is vulnerable to credential theft and policy injection.

- **Impact**: Critical: Ranger Admin credentials and full policy data transit the network unencrypted by default.
- **Rationale**: No code-level protection exists. Operator must explicitly configure HTTPS. ConfigValidator does not reject http://.

## Phase Progress

| Phase | Name | Completion |
|---|---|---|
| 1 | Business Context Analysis | 100% ✅ |
| 2 | Architecture Analysis | 100% ✅ |
| 3 | Threat Actor Analysis | 100% ✅ |
| 4 | Trust Boundary Analysis | 100% ✅ |
| 5 | Asset Flow Analysis | 100% ✅ |
| 6 | Threat Identification | 100% ✅ |
| 7 | Mitigation Planning | 100% ✅ |
| 7.5 | Code Validation Analysis | 100% ✅ |
| 8 | Residual Risk Analysis | 0% ⏳ |
| 9 | Output Generation and Documentation | 100% ✅ |

---

*This threat model report was generated automatically by the Threat Modeling MCP Server.*
