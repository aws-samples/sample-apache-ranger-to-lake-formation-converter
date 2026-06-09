package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.ranger.plugin.model.RangerPolicy;

import java.util.List;

class RangerExportModel {

    @JsonProperty("policies")
    List<RangerPolicy> policies;
}
