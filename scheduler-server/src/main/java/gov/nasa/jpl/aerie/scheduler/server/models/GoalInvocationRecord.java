package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;

import java.util.Map;

public record GoalInvocationRecord(
    GoalId id,
    String name,
    GoalType type,
    Map<String, SerializedValue> args,
    boolean simulateAfter) {}
