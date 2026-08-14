package gov.nasa.ammos.plandev.scheduler.server.models;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;

import java.util.Map;

public record GoalInvocationRecord(
    GoalId id,
    String name,
    GoalType type,
    Map<String, SerializedValue> args,
    boolean simulateAfter) {}
