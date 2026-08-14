package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;

import java.nio.file.Path;
import java.util.Map;

/**
 * plan details read from merlin services that are used by the scheduler
 *
 * @param planId unique identifier of the plan container
 * @param planRev the revision number of the plan currently stored in aerie
 * @param horizon declared time span of the plan, in both scheduler/merlin notations
 * @param modelId identifier of the mission model the plan relies on
 * @param modelPath relative file path within merlin filesystem to the mission model jar
 * @param modelName the registered name of the mission model to use from the model jar
 * @param modelVersion the version identifier of the mission model to use from the model jar
 * @param modelConfiguration plan-specific arguments to tune the behavior of the mission model
 */
public record PlanMetadata(
    PlanId planId,
    long planRev,
    PlanningHorizon horizon,
    long modelId,
    Path modelPath,
    String modelName,
    String modelVersion,
    Map<String, SerializedValue> modelConfiguration
)
{
}
