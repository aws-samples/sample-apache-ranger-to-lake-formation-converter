#!/usr/bin/env python3
"""Helpers for writing Apache Ranger Admin JSON exports.

The dicts these build are shaped exactly like the payload Ranger Admin's "Export"
button produces, so generated files can be fed straight to the assessment tool.

Shared by the demo fixture generators under demo/. Nothing here talks to AWS or
to Ranger; it is pure dict assembly.
"""

import uuid


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


class PolicyBuilder:
    """Assembles Ranger policies, allocating IDs per service.

    ID ranges are kept disjoint per service so a policy ID in an assessment
    report immediately tells you which Ranger service it came from.

    :param service_types: Ranger service instance name -> service type
        (e.g. ``{"acme_hive": "hive"}``).
    :param id_bases: service instance name -> first ID minus one for that service.
    :param guid_namespace: prefix for the deterministic policy GUIDs, so
        regenerating a fixture does not churn every GUID.
    """

    def __init__(self, service_types, id_bases, guid_namespace):
        self.service_types = service_types
        self.guid_namespace = guid_namespace
        self._next_id = dict(id_bases)

    def _alloc_id(self, service):
        self._next_id[service] += 1
        return self._next_id[service]

    def policy(self, service, name, resources, allow=None, description="", labels=None,
               deny=None, allow_exceptions=None, deny_exceptions=None,
               policy_type=0, data_mask=None, row_filter=None,
               validity=None, conditions=None, zone="", enabled=True,
               deny_all_else=False, priority=0):
        """Assemble one Ranger policy in export format."""
        pid = self._alloc_id(service)
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
            "serviceType": self.service_types[service],
            "options": {},
            "validitySchedules": validity or [],
            "policyLabels": labels or [],
            "conditions": conditions or [],
            "zoneName": zone,
            "isDenyAllElse": deny_all_else,
            "id": pid,
            "guid": str(uuid.uuid5(uuid.NAMESPACE_URL,
                                   f"{self.guid_namespace}/{service}/{name}")),
            "isEnabled": enabled,
            "version": 1,
        }


def export(policies, host_name, exported_by, export_time,
           ranger_version="2.4.0", query_time_ms=41):
    """Wrap policies in the top-level Ranger export envelope."""
    return {
        "metaDataInfo": {
            "Host name": host_name,
            "Exported by": exported_by,
            "Export time": export_time,
            "Ranger apache version": ranger_version,
        },
        "policies": policies,
        "startIndex": 0,
        "pageSize": len(policies),
        "totalCount": len(policies),
        "resultSize": len(policies),
        "queryTimeMS": query_time_ms,
    }
