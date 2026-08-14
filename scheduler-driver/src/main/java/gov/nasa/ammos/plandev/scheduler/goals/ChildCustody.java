package gov.nasa.jpl.aerie.scheduler.goals;

/**
 * describes how a goal is satisfied by shared activity instances
 */
public enum ChildCustody {

  /**
   * activity instances may be shared among multiple goals
   *
   * this should generally be the default as it allows synergy between goals
   */
  Jointly,

  /**
   * activity instances may not be shared among goals
   *
   * the goal must have its own individual copy of the activity instance,
   * even if all other constraints are met and even if all other goals
   * allow joint custody
   *
   * this may cause unnecessary replication of activities in the plan, but
   * may be desired in some cases where the activities are unique in some
   * way besides their constrainable parameters
   */
  Solely

}
