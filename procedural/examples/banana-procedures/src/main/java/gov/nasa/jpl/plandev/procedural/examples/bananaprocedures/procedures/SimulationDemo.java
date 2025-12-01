package gov.nasa.jpl.plandev.procedural.examples.bananaprocedures.procedures;

import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.procedural.scheduling.Goal;
import gov.nasa.jpl.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.jpl.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.jpl.plandev.procedural.timeline.collections.profiles.Real;
import gov.nasa.jpl.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@SchedulingProcedure
public record SimulationDemo(int quantity) implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {

    var simResults = plan.latestResults();
    if (simResults == null) simResults = plan.simulate();

    final var lowFruit = simResults.resource("/fruit", Real.deserializer()).lessThan(3.5).isolateTrue();
    final var bites = simResults.instances("BiteBanana");

    final var connections = lowFruit.starts().shift(Duration.MINUTE.negate())
                                    .connectTo(bites.ends(), false);

    for (final var connection: connections) {
      assert connection.to != null;
      plan.create(
          "GrowBanana",
          new DirectiveStart.Anchor(
              connection.to.directiveId,
              Duration.minutes(30),
              DirectiveStart.Anchor.AnchorPoint.End
          ),
          Map.of(
              "quantity", SerializedValue.of(1),
              "growingDuration", SerializedValue.of(Duration.HOUR.dividedBy(Duration.MICROSECOND))
          )
      );
    }

    plan.commit();
  }
}
