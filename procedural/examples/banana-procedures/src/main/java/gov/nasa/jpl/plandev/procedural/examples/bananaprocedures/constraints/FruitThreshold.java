package gov.nasa.jpl.plandev.procedural.examples.bananaprocedures.constraints;

import gov.nasa.jpl.plandev.procedural.constraints.Constraint;
import gov.nasa.jpl.plandev.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.jpl.plandev.procedural.constraints.Violations;
import gov.nasa.jpl.plandev.procedural.timeline.collections.profiles.Real;
import gov.nasa.jpl.plandev.procedural.timeline.plan.Plan;
import gov.nasa.jpl.plandev.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

@ConstraintProcedure
public record FruitThreshold(int threshold) implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    final var fruit = simResults.resource("/fruit", Real.deserializer());

    return Violations.on(
        fruit.lessThan(threshold),
        false
    );
  }
}
