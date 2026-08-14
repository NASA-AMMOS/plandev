package gov.nasa.jpl.aerie.e2e.procedural.constraints.external_events;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

@ConstraintProcedure
public record ExternalEventAttributeConstraint(String eventType, String codeValue) implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    // filter events on whether attribute "code" equals codeValue
    var events = plan.events(new EventQuery(null, eventType, null))
        .filter(true, e -> {
          if (e.attributes.get("code").asString().isPresent()) {
            return e.attributes.get("code").asString().get().equals(codeValue);
          }
          return false;
        });

    // if we have any events with that attribute value
    if (!events.collect().isEmpty()) {
      // ...mark those events as violating
      return new Violations(
          events.collectIntervals()
                .stream()
                .map(e -> new Violation(e.getInterval()))
                .toList()
      );
    }
    // otherwise, return an empty violations object.
    return new Violations();
  }
}
