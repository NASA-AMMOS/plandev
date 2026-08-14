package gov.nasa.jpl.aerie.scheduler.solver.optimizers;

import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;

import java.util.List;

public abstract class Optimizer {

  List<SchedulingActivity> currentGoalSolution = null;


  //incremental call
  public abstract boolean isBetterThanCurrent(List<SchedulingActivity> candidateGoalSolution);

}
