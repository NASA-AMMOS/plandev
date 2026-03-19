package gov.nasa.jpl.aerie.serializationbenchmark;

import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;

import java.util.List;
import java.util.Map;

/**
 * Activity with basic parameters. The complex serialization testing is done
 * via the Mission resources, not the activity parameters.
 */
@ActivityType("ComplexActivity")
public record ComplexActivity(
    String activityName,
    double targetX,
    double targetY,
    double targetZ,
    List<String> commandSequence,
    Map<String, Double> resourceRequirements
) {
  public ComplexActivity {
    if (activityName == null) activityName = "DefaultActivity";
    if (commandSequence == null) commandSequence = List.of();
    if (resourceRequirements == null) resourceRequirements = Map.of();
  }
}
