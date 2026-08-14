package gov.nasa.jpl.aerie.scheduler.solver.optimizers;

public class Optimizers {

  public static Optimizer earliestEndTime() {
    return new OptimizerEarliestEndTime();
  }

  public static Optimizer latestStartTime() {
    return new OptimizerLatestStartTime();
  }

}
