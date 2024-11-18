package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.GeneratorConstraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

public class ConstFruit extends GeneratorConstraint {
  @Override
  public void generate(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    final var fruit = simResults.resource("/fruit", Real.deserializer());

    violate(Violations.on(
        fruit.equalTo(4),
        false
    ));
  }
}
