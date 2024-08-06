package gov.nasa.ammos.aerie.procedural.examples.fooprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.CollectOptions;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulatedPlan;
import org.jetbrains.annotations.NotNull;

public class ConstFruit implements Constraint {
  @NotNull
  @Override
  public Violations run(SimulatedPlan plan, @NotNull CollectOptions options) {
    final var fruit = plan.resource("/fruit", Real::deserialize);

    return Violations.violateOn(
        fruit.equalTo(4),
        false
    );
  }
}
