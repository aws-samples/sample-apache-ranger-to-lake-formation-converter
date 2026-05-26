package com.example.ranger.lakeformation.simulator.remediation;

import com.example.ranger.lakeformation.simulator.validator.SimulatorPermission;
import com.example.ranger.lakeformation.simulator.validator.ValidationResult;
import com.example.ranger.lakeformation.simulator.workload.MutationOperation;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ReproductionBundle(
    Instant detectedAt,
    long violationDetectedAfterCycle,
    long lastSuccessfulCycle,
    List<MutationOperation> mutations,      // all mutations logged up to this point
    String rangerSnapshotJson,              // all current Ranger policies as JSON string
    Set<SimulatorPermission> lfActual,      // actual LF permissions at time of violation
    Set<SimulatorPermission> lfExpected,    // expected permissions from Phase2 computer
    ValidationResult validationResult       // the violation result
) {}
