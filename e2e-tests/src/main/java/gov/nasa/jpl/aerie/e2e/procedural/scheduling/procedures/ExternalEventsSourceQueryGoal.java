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
public record ExternalEventsSourceQueryGoal() implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {

    // extract events belonging to the second source
    EventQuery eventQuery = new EventQuery(
        null,
        null,
        List.of(new EventQuery.SourceQuery("NewTest.json", "TestGroup_2"))
    );

    for (final var e: plan.events(eventQuery)) {
      // filter events that we schedule off of by key
      if (e.key.contains("01")) {
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
