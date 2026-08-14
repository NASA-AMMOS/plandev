package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Timestamp;

import java.util.List;
import java.util.Map;

public record Specification(
    SpecificationId specificationId,
    long specificationRevision,
    PlanId planId,
    long planRevision,
    Timestamp horizonStartTimestamp,
    Timestamp horizonEndTimestamp,
    Map<String, SerializedValue> simulationArguments,
    boolean analysisOnly,
    List<GoalInvocationRecord> goalsByPriority,
    List<SchedulingConditionRecord> schedulingConditions
) {}
