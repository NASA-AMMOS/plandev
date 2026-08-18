package gov.nasa.ammos.plandev.e2e.procedural.constraints.external_events;

import gov.nasa.ammos.plandev.procedural.constraints.Constraint;
import gov.nasa.ammos.plandev.procedural.constraints.Violations;
import gov.nasa.ammos.plandev.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.plandev.procedural.timeline.collections.Windows;
import gov.nasa.ammos.plandev.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.plandev.procedural.timeline.plan.Plan;
import gov.nasa.ammos.plandev.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

@ConstraintProcedure
public record ExternalEventActivityOverlapConstraint(String eventType) implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    // create window of events
    var events = new Windows(plan.events(new EventQuery(null, eventType, null))
        .filter(true, e -> {
          if (e.attributes.get("code").asString().isPresent()) {
            return e.attributes.get("code").asString().get().equals("B");
          }
          return false;
        })
        .collectIntervals());

    // create window of activities
    var activities = new Windows(simResults.instances().collectIntervals());

    // check if any overlap...
    var overlap = events.intersection(activities);
    if (!overlap.collect().isEmpty()) {
      // ...and then set violations where it happens...
      return Violations.inside(new Windows(overlap));
    }
    // ...otherwise, return an empty violations object.
    return new Violations();
  }
}
