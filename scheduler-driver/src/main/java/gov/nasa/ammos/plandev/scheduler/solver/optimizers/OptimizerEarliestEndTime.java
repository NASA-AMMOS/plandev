package gov.nasa.jpl.aerie.scheduler.solver.optimizers;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;

import java.util.List;

public class OptimizerEarliestEndTime extends Optimizer {

  Duration currentEarliestEndTime = null;

  @Override
  public boolean isBetterThanCurrent(List<SchedulingActivity> candidateGoalSolution) {
    SchedulingActivity act = SchedulingActivity.getActWithEarliestEndTime(candidateGoalSolution);
    if(act == null || act.duration() != null) {
      throw new IllegalStateException("Cannot optimize on uninstantiated activities");
    }
    if (currentEarliestEndTime == null || act.getEndTime().shorterThan(currentEarliestEndTime)) {
      currentGoalSolution = candidateGoalSolution;
      currentEarliestEndTime = act.getEndTime();
      return true;
    }
    return false;
  }


}
