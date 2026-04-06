package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum representing Lake Formation permission types.
 * Maps to the permission actions available in AWS Lake Formation.
 */
public enum LFPermission {
    SELECT("SELECT"),
    INSERT("INSERT"),
    DELETE("DELETE"),
    DESCRIBE("DESCRIBE"),
    ALTER("ALTER"),
    DROP("DROP"),
    CREATE_DATABASE("CREATE_DATABASE"),
    CREATE_TABLE("CREATE_TABLE"),
    DATA_LOCATION_ACCESS("DATA_LOCATION_ACCESS");

    private final String value;

    LFPermission(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static LFPermission fromValue(String value) {
        for (LFPermission permission : values()) {
            if (permission.value.equalsIgnoreCase(value)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unknown LFPermission: " + value);
    }
}
