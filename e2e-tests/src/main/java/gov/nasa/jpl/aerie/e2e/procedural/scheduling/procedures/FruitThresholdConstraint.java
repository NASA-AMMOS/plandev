package gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.WithDefaults;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

@ConstraintProcedure
public record FruitThresholdConstraint(int lowerBound, int upperBound) implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    final var fruit = simResults.resource("/fruit", Real.deserializer());

    return Violations.on(
        fruit.lessThan(upperBound).and(fruit.greaterThan(lowerBound)),
        false
    );
  }

  @WithDefaults
  public static class Template{
    public int lowerBound = 5;
  }
}
