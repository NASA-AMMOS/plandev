package gov.nasa.jpl.plandev.e2e.procedural.scheduling.procedures;

import gov.nasa.jpl.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.jpl.plandev.procedural.scheduling.Goal;
import gov.nasa.jpl.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.jpl.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.plandev.procedural.timeline.plan.EventQuery;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@SchedulingProcedure
public record ExternalEventsSimpleGoal() implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    EventQuery eventQuery = new EventQuery("TestGroup", null, null);

    for (final var e: plan.events(eventQuery)) {
      plan.create("BiteBanana", new DirectiveStart.Absolute(e.getInterval().start), Map.of("biteSize", SerializedValue.of(1)));
    }
    plan.commit();
  }
}
