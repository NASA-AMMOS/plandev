package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.Timestamp;

public record PlanRecord(
    long id,
    long revision,
    String name,
    long missionModelId,
    Timestamp startTime,
    Timestamp endTime
) {}
