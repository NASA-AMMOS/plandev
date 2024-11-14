package gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

@SchedulingProcedure
public record ExternalEventsSourceAttributeQueryGoal() implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    // extract all events
    for (final var e: plan.events()) {
      // filter events that we schedule off of by their source's attributes
      var version = e.source.attributes.get("version").asInt();
      if (version.isPresent() && version.get() == 2) {
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
