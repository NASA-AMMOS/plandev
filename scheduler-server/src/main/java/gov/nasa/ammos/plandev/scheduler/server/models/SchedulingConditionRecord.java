package gov.nasa.jpl.aerie.scheduler.server.models;

public record SchedulingConditionRecord(
    SchedulingConditionId id,
    long revision,
    String name,
    SchedulingConditionSource source
) {}
