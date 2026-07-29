package gov.nasa.ammos.plandev.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.plandev.procedural.scheduling.ActivityAutoDelete;
import gov.nasa.ammos.plandev.procedural.scheduling.Goal;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.plandev.procedural.timeline.plan.Plan;
import gov.nasa.ammos.plandev.procedural.timeline.plan.SimulationResults;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@SchedulingProcedure
public record AnchorCascadeDeleteGoal() implements Goal {

  @Override
  public ActivityAutoDelete shouldDeletePastCreations(
      @NotNull final Plan plan,
      @Nullable final SimulationResults simResults) {
    // Delete activities created by previous runs of this goal, using Cascade to
    // handle anchored activities
    return new ActivityAutoDelete.AtBeginning(DeletedAnchorStrategy.Cascade, false);
  }

  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var ids = new ActivityDirectiveId[3];

    ids[0] = plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.HOUR),
        Map.of("biteSize", SerializedValue.of(1))
    );
    ids[1] = plan.create(
        "BiteBanana",
        new DirectiveStart.Anchor(ids[0], Duration.HOUR, DirectiveStart.Anchor.AnchorPoint.End),
        Map.of("biteSize", SerializedValue.of(2))
    );
    ids[2] = plan.create(
        "BiteBanana",
        new DirectiveStart.Anchor(ids[1], Duration.HOUR, DirectiveStart.Anchor.AnchorPoint.Start),
        Map.of("biteSize", SerializedValue.of(3))
    );

    plan.commit();
  }
}
