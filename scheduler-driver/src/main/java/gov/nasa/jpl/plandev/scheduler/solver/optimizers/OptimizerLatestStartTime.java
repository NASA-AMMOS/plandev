package gov.nasa.jpl.plandev.scheduler.solver.optimizers;

import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.scheduler.model.SchedulingActivity;

import java.util.List;

public class OptimizerLatestStartTime extends Optimizer {

  Duration currentLatestStartTime = null;

  @Override
  public boolean isBetterThanCurrent(List<SchedulingActivity> candidateGoalSolution) {
    SchedulingActivity act = SchedulingActivity.getActWithLatestStartTime(candidateGoalSolution);
    if(act == null || act.getEndTime() == null) {
      throw new IllegalStateException("Cannot optimize on uninstantiated activities");
    }
    if (currentLatestStartTime == null || act.startOffset().longerThan(currentLatestStartTime)) {
      currentGoalSolution = candidateGoalSolution;
      currentLatestStartTime = act.startOffset();
      return true;
    }
    return false;
  }

}
