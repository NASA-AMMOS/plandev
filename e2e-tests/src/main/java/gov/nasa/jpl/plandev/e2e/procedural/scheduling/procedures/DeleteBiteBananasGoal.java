package gov.nasa.jpl.plandev.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.types.ActivityDirectiveId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Deletes all Bite Bananas with extreme prejudice. Used to test that updated
 * anchors are saved in the database properly.
 */
@SchedulingProcedure
public record DeleteBiteBananasGoal(DeletedAnchorStrategy anchorStrategy) implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    plan.directives("BiteBanana").forEach($ -> plan.delete($, anchorStrategy));
    plan.commit();
  }
}
