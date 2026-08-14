package gov.nasa.ammos.plandev.e2e.procedural.constraints;

import gov.nasa.ammos.plandev.procedural.constraints.Constraint;
import gov.nasa.ammos.plandev.procedural.constraints.Violations;
import gov.nasa.ammos.plandev.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.WithDefaults;
import gov.nasa.ammos.plandev.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.plandev.procedural.timeline.plan.Plan;
import gov.nasa.ammos.plandev.procedural.timeline.plan.SimulationResults;
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
    ).withDefaultMessage("Fruit count is outside of boundaries: [%d, %d]".formatted(lowerBound, upperBound));
  }

  @WithDefaults
  public static class Template{
    public int lowerBound = 5;
  }
}
