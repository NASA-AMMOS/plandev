package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.types.Timestamp;

public record PlanRecord(
    long id,
    long revision,
    String name,
    long missionModelId,
    Timestamp startTime,
    Timestamp endTime
) {}
