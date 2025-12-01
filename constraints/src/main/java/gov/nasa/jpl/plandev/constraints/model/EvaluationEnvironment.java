package gov.nasa.jpl.plandev.constraints.model;

import gov.nasa.jpl.plandev.constraints.time.Interval;
import gov.nasa.jpl.plandev.constraints.time.Spans;

import java.util.Map;

/** A container for additional context needed for Constraints AST evaluation. */
public record EvaluationEnvironment(
    Map<String, ActivityInstance> activityInstances,
    Map<String, Spans> spansInstances,
    Map<String, Interval> intervals,
    Map<String, LinearProfile> realExternalProfiles,
    Map<String, DiscreteProfile> discreteExternalProfiles
) {
  public EvaluationEnvironment() {
    this(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  public EvaluationEnvironment(Map<String, LinearProfile> realExternalProfiles, Map<String, DiscreteProfile> discreteExternalProfiles) {
    this(Map.of(), Map.of(), Map.of(), realExternalProfiles, discreteExternalProfiles);
  }
}
