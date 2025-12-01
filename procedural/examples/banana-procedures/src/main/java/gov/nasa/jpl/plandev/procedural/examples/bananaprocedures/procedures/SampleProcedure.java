package gov.nasa.jpl.plandev.procedural.examples.bananaprocedures.procedures;

import gov.nasa.jpl.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.jpl.plandev.procedural.scheduling.Goal;
import gov.nasa.jpl.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.jpl.plandev.procedural.scheduling.plan.NewDirective;
import gov.nasa.jpl.plandev.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@SchedulingProcedure
public record SampleProcedure(int quantity) implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var firstTime = Duration.hours(24);
    final var step = Duration.hours(6);

    var currentTime = firstTime;
    for (var i = 0; i < quantity; i++) {
      plan.create(
          new NewDirective(
              new AnyDirective(Map.of()),
              "It's a bite banana activity",
              "BiteBanana",
              new DirectiveStart.Absolute(currentTime)
          )
      );
      currentTime = currentTime.plus(step);
    }
    plan.commit();
  }
}
