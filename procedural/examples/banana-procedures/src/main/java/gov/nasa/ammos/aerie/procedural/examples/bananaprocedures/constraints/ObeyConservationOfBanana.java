package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

/**
 * Checks that you do not eat more bananas than have been picked.
 *
 * The BiteBanana and PickBanana activities don't actually interact on
 * a common resource, because the meanings of the "fruit" and "plant" resources are fairly
 * arbitrary and inconsistent. So this constraint mocks the behavior they
 * would have if they were incrementing and decrementing a common resource,
 * and makes sure the total stock of picked bananas doesn't go below zero.
 */
@ConstraintProcedure
public record ObeyConservationOfBanana() implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    final var pickActivities = simResults.instances("PickBanana");
    final var biteActivities = simResults.instances("BiteBanana");

    var bananaCounter = new Real(0);
    for (final var pick: pickActivities) {
      bananaCounter = bananaCounter.plus(Real.step(
          pick.getInterval().start,
          pick.inner.arguments.get("quantity").asInt().get()
      ));
    }
    for (final var bite: biteActivities) {
      bananaCounter = bananaCounter.minus(Real.step(
          bite.getInterval().start,
          bite.inner.arguments.get("biteSize").asReal().get()
      ));
    }

    return Violations.on(
        bananaCounter.lessThan(0),
        true
    );
  }
}
