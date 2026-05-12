package gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

@ConstraintProcedure
public record ExternalEventAbsenceConstraint() implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    var events = plan.events();

    // if we have any events
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
