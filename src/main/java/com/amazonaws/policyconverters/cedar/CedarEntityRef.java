package com.amazonaws.policyconverters.cedar;

import java.util.Objects;

/**
 * Pairs a Cedar entity type with its identifier string.
 * For example, entityType="DataCatalog::Database" and entityId="arn:aws:glue:us-east-1:123456789012:database/analytics_db".
 */
public final class CedarEntityRef {

    private final String entityType;
    private final String entityId;

    public CedarEntityRef(String entityType, String entityId) {
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CedarEntityRef that = (CedarEntityRef) o;
        return Objects.equals(entityType, that.entityType) && Objects.equals(entityId, that.entityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityType, entityId);
    }

    @Override
    public String toString() {
        return entityType + "::\"" + entityId + "\"";
    }
}
