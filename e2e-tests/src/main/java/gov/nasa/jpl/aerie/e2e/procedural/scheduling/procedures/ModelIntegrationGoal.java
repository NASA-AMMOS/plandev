package gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.timeline.util.WithModel;
import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Creates a bite banana every time /producer changes
 */
@SchedulingProcedure
public record ModelIntegrationGoal() implements Goal, WithModel<Mission> {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var changes = plan.simulate().resource(model().producer).changes().highlightTrue();
    for (final var interval: changes) {
      plan.create("BiteBanana", new DirectiveStart.Absolute(interval.start), Map.of("biteSize", SerializedValue.of(1)));
    }
    plan.commit();
  }
}
