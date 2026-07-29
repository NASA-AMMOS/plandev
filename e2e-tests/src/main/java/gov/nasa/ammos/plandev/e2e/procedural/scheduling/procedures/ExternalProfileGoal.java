package gov.nasa.ammos.plandev.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.plandev.procedural.scheduling.Goal;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.plandev.procedural.timeline.collections.profiles.Booleans;
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@SchedulingProcedure
public record ExternalProfileGoal() implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var myBoolean = plan.resource("/my_boolean", Booleans.deserializer());
    for (final var interval: myBoolean.highlightTrue()) {
      plan.create(
          "BiteBanana",
          new DirectiveStart.Absolute(interval.start),
          Map.of("biteSize", SerializedValue.of(1))
      );
    }

    plan.commit();
  }
}
