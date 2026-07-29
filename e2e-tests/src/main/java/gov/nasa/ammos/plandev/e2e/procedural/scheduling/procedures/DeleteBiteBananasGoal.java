package gov.nasa.ammos.plandev.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.plandev.procedural.scheduling.Goal;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.EditablePlan;
import org.jetbrains.annotations.NotNull;

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
