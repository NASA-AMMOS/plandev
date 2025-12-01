package gov.nasa.jpl.plandev.merlin.server.remotes.postgres;

import gov.nasa.jpl.plandev.types.Timestamp;

public record PlanRecord(
    long id,
    long revision,
    String name,
    long missionModelId,
    Timestamp startTime,
    Timestamp endTime
) {}
