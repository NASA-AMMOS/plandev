package gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Creates three activities in a chain of anchors, then deletes one.
 * If `whichToDelete` is negative, this leaves all three activities.
 * If `rollback` is true, this will roll the edit back before finishing.
 */
@SchedulingProcedure
public record ActivityDeletionGoal(int whichToDelete, DeletedAnchorStrategy anchorStrategy, boolean rollback) implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var ids = new ActivityDirectiveId[3];

    ids[0] = plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.HOUR),
        Map.of("biteSize", SerializedValue.of(0))
    );
    ids[1] = plan.create(
        "BiteBanana",
        new DirectiveStart.Anchor(ids[0], Duration.HOUR, DirectiveStart.Anchor.AnchorPoint.End),
        Map.of("biteSize", SerializedValue.of(1))
    );
    ids[2] = plan.create(
        "BiteBanana",
        new DirectiveStart.Anchor(ids[1], Duration.HOUR, DirectiveStart.Anchor.AnchorPoint.Start),
        Map.of("biteSize", SerializedValue.of(2))
    );

    plan.commit();

    if (whichToDelete >= 0) {
      plan.delete(ids[whichToDelete], anchorStrategy);
    }

    if (rollback) plan.rollback();
    else plan.commit();
  }
}
