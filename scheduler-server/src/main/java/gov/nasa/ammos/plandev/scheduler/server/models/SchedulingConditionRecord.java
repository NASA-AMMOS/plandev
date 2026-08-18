package gov.nasa.ammos.plandev.scheduler.server.models;

public record SchedulingConditionRecord(
    SchedulingConditionId id,
    long revision,
    String name,
    SchedulingConditionSource source
) {}
