package gov.nasa.ammos.plandev.constraints.model;

import gov.nasa.ammos.plandev.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Spans;

import java.util.List;
import java.util.Map;

/** A container for additional context needed for Constraints AST evaluation. */
public record EvaluationEnvironment(
    Map<String, ActivityInstance> activityInstances,
    Map<String, Spans> spansInstances,
    Map<String, Interval> intervals,
    Map<String, LinearProfile> realExternalProfiles,
    Map<String, DiscreteProfile> discreteExternalProfiles,
    Map<String, List<ExternalEvent>> externalEventsByDerivationGroup
) {
  public EvaluationEnvironment() {
    this(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  public EvaluationEnvironment(Map<String, LinearProfile> realExternalProfiles, Map<String, DiscreteProfile> discreteExternalProfiles, Map<String, List<ExternalEvent>> externalEventsByDerivationGroup) {
    this(Map.of(), Map.of(), Map.of(), realExternalProfiles, discreteExternalProfiles, externalEventsByDerivationGroup);
  }
}
