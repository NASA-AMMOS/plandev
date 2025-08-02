package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.NewDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
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
