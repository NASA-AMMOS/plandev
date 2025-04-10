package gov.nasa.jpl.aerie.e2e.procedural.constraints.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

/**
 * This is a simple constraint that examines plan properties and event properties. Unlike scheduling goals,
 *    constraints involving events never place activities that might affect resources or other activities; as such,
 *    constraints really lead to no impact on the plan. Meaning, the most meaningful constraints we can devise using
 *    events typically involve resource values or activity instances coinciding with events. To demonstrate our
 *    ability to access both without providing an exhaustive set of constraints that cover all event functionality,
 *    which would be extremely redundant, we provide simply this one constraint.
 */
@ConstraintProcedure
public record EventCounter() implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    final var pickActivities = simResults.instances("PickBanana");
    EventQuery eventQuery = new EventQuery("Weather Default", null, null);

    var bananaCounter = new Real(pickActivities.collect().size());
    var events = plan.events(eventQuery);

    return Violations.on(
        bananaCounter.equalTo(events.collect().size()),
        false
    );
  }
}
