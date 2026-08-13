package gov.nasa.jpl.aerie.e2e.procedural.goals.external_events;

import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@SchedulingProcedure
public record ExternalEventsSimpleGoal(String derivationGroup) implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    EventQuery eventQuery = new EventQuery(derivationGroup, null, null);

    for (final var e: plan.events(eventQuery)) {
      plan.create("BiteBanana", new DirectiveStart.Absolute(e.getInterval().start), Map.of("biteSize", SerializedValue.of(1)));
    }
    plan.commit();
  }
}
