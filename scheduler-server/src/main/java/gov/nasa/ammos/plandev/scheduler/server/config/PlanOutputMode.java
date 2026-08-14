package gov.nasa.jpl.aerie.scheduler.server.config;

/**
 * controls how the scheduling service outputs its scheduled activities
 */
public enum PlanOutputMode {

  /**
   * create a new plan container to hold the output schedule
   */
  CreateNewOutputPlan,

  /**
   * update the input plan container itself with the new activities in the output schedule
   */
  UpdateInputPlanWithNewActivities

}
