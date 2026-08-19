package gov.nasa.ammos.plandev.e2e.procedural.goals.external_events;

import gov.nasa.ammos.plandev.procedural.scheduling.Goal;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@SchedulingProcedure
public record ExternalEventsEventAttributeQueryGoal() implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    // extract all events
    for (final var e: plan.events()) {
      // filter events that we schedule off of by their source's attributes
      var version = e.attributes.get("projectUser").asString();
      if (version.isPresent() && version.get().equals("UserA")) {
        plan.create(
            "BiteBanana",
            // place the directive such that it is coincident with the event's start
            new DirectiveStart.Absolute(e.getInterval().start),
            Map.of("biteSize", SerializedValue.of(1)));
      }
    }
    plan.commit();
  }
}
