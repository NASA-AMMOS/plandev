package gov.nasa.ammos.plandev.scheduler.server.remotes.postgres;

import gov.nasa.ammos.plandev.scheduler.server.models.PlanId;
import gov.nasa.ammos.plandev.types.MissionModelId;

public record PlanReadOnlyCheckResult(
    PlanId planId,
    boolean planReadOnly,
    MissionModelId modelId,
    boolean modelExecutable
) {
}
