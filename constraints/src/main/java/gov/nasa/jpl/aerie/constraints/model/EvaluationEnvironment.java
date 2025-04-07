package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;

import java.util.List;
import java.util.Map;

/** A container for additional context needed for Constraints AST evaluation. */
public record EvaluationEnvironment(
    Map<String, ActivityInstance> activityInstances,
    Map<String, Spans> spansInstances,
    Map<String, Interval> intervals,
    Map<String, List<ExternalEvent>> eventsByDerivationGroup,
    Map<String, LinearProfile> realExternalProfiles,
    Map<String, DiscreteProfile> discreteExternalProfiles
) {
  public EvaluationEnvironment() {
    this(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  public EvaluationEnvironment(Map<String, LinearProfile> realExternalProfiles, Map<String, DiscreteProfile> discreteExternalProfiles, Map<String, List<ExternalEvent>> eventsByDerivationGroup) {
    this(Map.of(), Map.of(), Map.of(), eventsByDerivationGroup, realExternalProfiles, discreteExternalProfiles);
  }
}
