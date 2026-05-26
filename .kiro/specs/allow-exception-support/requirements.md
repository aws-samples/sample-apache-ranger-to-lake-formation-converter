# Requirements Document

## Introduction

This feature adds allow exception handling to the Ranger-to-Cedar-to-LakeFormation policy conversion pipeline. In Apache Ranger, `allowExceptions` items override allow rules: "everyone in group X gets access, except user Y." Currently the pipeline silently ignores allow exceptions, which causes over-granting — user Y would incorrectly receive access. This feature implements allow exception support across both conversion stages (RangerToCedarConverter and CedarToLFConverter), adds a new `@allowException` Cedar annotation to enable correct resolution of effective grants, and removes the legacy PolicyConverter class which is now redundant since the Cedar-based pipeline fully handles all four Ranger item types.

## Glossary

- **RangerToCedarConverter**: The component that transforms Apache Ranger policies into Cedar policy statements.
- **CedarToLFConverter**: The component that parses Cedar policy statements and resolves effective grants into LakeFormation permission operations.
- **Allow_Item**: A Ranger `policyItems` entry that grants access (maps to a Cedar `permit` statement).
- **Deny_Item**: A Ranger `denyPolicyItems` entry that denies access (maps to a Cedar `forbid` statement).
- **Deny_Exception**: A Ranger `denyExceptions` entry that overrides a deny (maps to a Cedar `permit` with `@denyException("true")` annotation).
- **Allow_Exception**: A Ranger `allowExceptions` entry that overrides an allow. The principal in the Allow_Exception is excluded from the grant that the Allow_Item would otherwise produce.
- **Cedar_PolicySet**: The intermediate Cedar policy representation used between the two conversion stages.
- **Effective_Grant**: The final resolved permission for a given (principal, action, resource) tuple after applying all allow, deny, deny-exception, and allow-exception rules.
- **GapReporter**: The component that records unsupported or partially-supported Ranger features as gap entries.
- **ALLOW_EXCEPTION_Annotation**: The `@allowException("true")` Cedar annotation used to mark `forbid` statements generated from Ranger Allow_Exception items.

## Requirements

### Requirement 1: RangerToCedarConverter Generates Forbid Statements for Allow Exceptions

**User Story:** As a policy administrator, I want Ranger allow exception items to be converted into Cedar forbid statements with an `@allowException` annotation, so that allow exceptions are represented in the Cedar intermediate format and not silently ignored.

#### Acceptance Criteria

1. WHEN a Ranger policy contains `allowExceptions` items, THE RangerToCedarConverter SHALL generate a Cedar `forbid` statement for each (principal, action, resource) combination in the Allow_Exception items.
2. THE RangerToCedarConverter SHALL annotate each forbid statement generated from an Allow_Exception item with `@allowException("true")`.
3. THE RangerToCedarConverter SHALL annotate each forbid statement generated from an Allow_Exception item with `@source` containing the originating Ranger policy ID prefixed by the service type.
4. WHEN a Ranger policy contains Allow_Exception items with multiple principals, THE RangerToCedarConverter SHALL generate one forbid statement per principal per action per resource combination.
5. WHEN a Ranger policy contains Allow_Exception items with multiple access types, THE RangerToCedarConverter SHALL map each access type to Cedar actions using the registered SourcePolicyAdapter and generate forbid statements for each mapped action.
6. WHEN a Ranger policy contains Allow_Exception items with principals that cannot be resolved by the PrincipalMapper, THE RangerToCedarConverter SHALL skip those principals and continue processing resolved principals.

### Requirement 2: CedarToLFConverter Resolves Allow Exception Semantics

**User Story:** As a policy administrator, I want the CedarToLFConverter to suppress grants for principals listed in allow exceptions, so that allow exceptions correctly prevent over-granting in LakeFormation.

#### Acceptance Criteria

1. WHEN a Cedar PolicySet contains a `permit` and a `forbid` with `@allowException("true")` for the same (principal, action, resource), THE CedarToLFConverter SHALL suppress the GRANT operation for that tuple.
2. WHEN a Cedar PolicySet contains a `permit` for principal A and a `forbid` with `@allowException("true")` for principal B on the same (action, resource), THE CedarToLFConverter SHALL produce a GRANT for principal A and suppress the GRANT for principal B.
3. THE CedarToLFConverter SHALL parse the `@allowException("true")` annotation from Cedar forbid statements.
4. WHEN a Cedar PolicySet contains both a regular `forbid` (deny) and a `forbid` with `@allowException("true")` for the same (principal, action, resource), THE CedarToLFConverter SHALL suppress the GRANT for that tuple regardless of which forbid type is present.

### Requirement 3: Four-Way Interaction of Allow, Deny, and Their Exceptions

**User Story:** As a policy administrator, I want all four Ranger item types (allow, deny, denyException, allowException) to interact correctly, so that the effective grants match Ranger's authorization semantics.

#### Acceptance Criteria

1. WHEN a (principal, action, resource) has an Allow_Item, a Deny_Item, a Deny_Exception, and an Allow_Exception, THE CedarToLFConverter SHALL suppress the GRANT because the Allow_Exception overrides the allow regardless of deny-exception status.
2. WHEN a (principal, action, resource) has an Allow_Item and an Allow_Exception but no Deny_Item, THE CedarToLFConverter SHALL suppress the GRANT because the Allow_Exception overrides the allow.
3. WHEN a (principal, action, resource) has an Allow_Item and a Deny_Item and a Deny_Exception but no Allow_Exception, THE CedarToLFConverter SHALL produce a GRANT because the Deny_Exception overrides the deny.
4. WHEN a (principal, action, resource) has only an Allow_Item, THE CedarToLFConverter SHALL produce a GRANT.
5. WHEN a (principal, action, resource) has an Allow_Item and a Deny_Item but no Deny_Exception and no Allow_Exception, THE CedarToLFConverter SHALL suppress the GRANT because deny overrides allow.

### Requirement 4: End-to-End Pipeline Correctness for Allow Exceptions

**User Story:** As a policy administrator, I want a Ranger policy with allow exceptions to produce correct LakeFormation operations through the full RangerToCedarConverter → CedarToLFConverter pipeline, so that excepted principals do not receive unintended access.

#### Acceptance Criteria

1. WHEN a Ranger policy grants SELECT to group_role and has an Allow_Exception for user_y on the same resource, THE pipeline SHALL produce a GRANT for group_role and suppress the GRANT for user_y.
2. WHEN a Ranger policy has Allow_Exception items for all principals in the Allow_Items on the same (action, resource), THE pipeline SHALL produce zero GRANT operations for that (action, resource).
3. WHEN a Ranger policy has Allow_Exception items for a subset of principals, THE pipeline SHALL produce GRANT operations only for the non-excepted principals.
4. FOR ALL valid Ranger policies with Allow_Exception items, converting through RangerToCedarConverter then CedarToLFConverter SHALL produce the same effective grants as converting through RangerToCedarConverter then CedarToLFConverter with any reordering of the Cedar statements (confluence property).

### Requirement 5: Remove Legacy PolicyConverter

**User Story:** As a developer, I want the legacy PolicyConverter class and all its references and tests removed from the codebase, so that the project only maintains the Cedar-based conversion pipeline (RangerToCedarConverter → CedarToLFConverter) which fully handles all four Ranger item types (allow, deny, denyException, allowException).

#### Acceptance Criteria

1. THE build system SHALL compile and all remaining tests SHALL pass after the removal of the legacy PolicyConverter class.
2. THE PolicyConverter class at `src/main/java/com/amazonaws/policyconverters/ranger/PolicyConverter.java` SHALL be deleted from the codebase.
3. THE PolicyConverterTest class at `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java` SHALL be deleted from the codebase.
4. THE PolicyConverterPropertyTest class at `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterPropertyTest.java` SHALL be deleted from the codebase.
5. IF any other source files contain imports or references to the PolicyConverter class, THEN THE build system SHALL remove those references.
