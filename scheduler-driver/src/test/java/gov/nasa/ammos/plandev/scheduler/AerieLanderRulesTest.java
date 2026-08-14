//package gov.nasa.jpl.aerie.scheduler;
//
//import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
//import gov.nasa.jpl.aerie.scheduler.goals.Goal;
//import gov.nasa.jpl.aerie.scheduler.model.ActivityInstance;
//import gov.nasa.jpl.aerie.scheduler.model.Plan;
//import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
//import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
//import gov.nasa.jpl.aerie.scheduler.model.Problem;
//import gov.nasa.jpl.aerie.scheduler.solver.PrioritySolver;
//import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//
//import java.util.ArrayList;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
//public class AerieLanderRulesTest {
//
//  @BeforeEach
//  void setUp(){
//    planningHorizon = new PlanningHorizon(TestUtility.timeFromEpochSeconds(0), TestUtility.timeFromEpochSeconds(48 * 3600));
//    MissionModel<?> aerieLanderMissionModel = AerieLanderTestUtility.getMerlinSightMissionModel();
//    final var aerieLanderSchedulerModel = AerieLanderTestUtility.getMerlinSightSchedulerModel();
//    rules = new AerieLanderRules(aerieLanderMissionModel, planningHorizon, aerieLanderSchedulerModel);
//    plan = makeEmptyPlan();
//    smallProblem = new Problem(aerieLanderMissionModel, planningHorizon, rules.getSimulationFacade(), aerieLanderSchedulerModel);
//  }
//
//  private PlanningHorizon planningHorizon;
//  private AerieLanderRules rules;
//  private Problem smallProblem;
//  private Plan plan;
//  /** constructs an empty plan with the test model/horizon **/
//  private PlanInMemory makeEmptyPlan() {
//    return new PlanInMemory();
//  }
//
//  public void schedule(final boolean analysisOnly){
//    smallProblem.setInitialPlan(plan);
//    rules.getGlobalConstraints().forEach(smallProblem::add);
//    var solver = new PrioritySolver(smallProblem, analysisOnly);
//    plan = solver.getNextSolution().get();
//    solver.printEvaluation();
//    AerieLanderTestUtility.printPlan(plan);
//  }
//
//  @Test
//  public void secondRuleAnalysis(){
//    final var goals = new ArrayList<>(rules.getSecondRuleGoals().values());
//    smallProblem.setGoals(goals);
//    final var activityInstancePresent = new ActivityInstance(rules.getActivityType("IDAMoveArm"), planningHorizon.fromStart("P1D"), Duration.of(20, Duration.MINUTE), null, true);
//    plan.add(activityInstancePresent);
//    schedule(true);
//    //assert than the procedural goal has a score equal to 0, that the association has been performed
//    assertEquals(0, plan.getEvaluation().forGoal(goals.get(0)).getScore());
//    //...but that no other activity was inserted to satisfy other goals (as this is analysis-only)
//    assertEquals(1, plan.getActivitiesByTime().size());
//  }
//
//  @Test
//  public void firstRule() {
//    smallProblem.setGoals(new ArrayList<>(rules.getFirstRuleGoals().values()));
//    schedule(false);
//    var time = planningHorizon.getStartAerie().plus(Duration.MINUTE);
//    assertTrue(TestUtility.activityStartingAtTime(plan, time, rules.getActivityType("SSAMonitoring")));
//    assertTrue(TestUtility.activityStartingAtTime(plan, time, rules.getActivityType("HeatProbeTemP")));
//    for(var t = Duration.HOUR; t.shorterThan(planningHorizon.getEndAerie()); t = t.plus(Duration.HOUR)){
//      assertTrue(TestUtility.activityStartingAtTime(plan, time, rules.getActivityType("HeatProbeTemP")));
//    }
//  }
//  @Test
//  public void secondRule() {
//    smallProblem.setGoals(new ArrayList<>(rules.getSecondRuleGoals().values()));
//    schedule(false);
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT23H27M"),
//                                        planningHorizon.fromStart("PT23H30M"), rules.getActivityType("IDAHeatersOn")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT23H29M"),
//                                        planningHorizon.fromStart("PT23H44M"), rules.getActivityType("IDCHeatersOn")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT23H39M"),
//                                        planningHorizon.fromStart("PT23H54M"), rules.getActivityType("ICCHeatersOn")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("PT23H54M"),
//                                        planningHorizon.fromStart("P1D"), rules.getActivityType("ICCImages")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1D"),
//                                        planningHorizon.fromStart("P1DT20M"), rules.getActivityType("IDAMoveArm")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT14M"),
//                                        planningHorizon.fromStart("P1DT20M"), rules.getActivityType("IDCImages")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT20M"),
//                                        planningHorizon.fromStart("P1DT40M"), rules.getActivityType("IDAGrapple")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT40M"),
//                                        planningHorizon.fromStart("P1DT1H40M"), rules.getActivityType("IDAMoveArm")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT40M"),
//                                        planningHorizon.fromStart("P1DT46M"), rules.getActivityType("IDCImages")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT1H34M"),
//                                        planningHorizon.fromStart("P1DT1H40M"), rules.getActivityType("IDCImages")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT1H40M"),
//                                        planningHorizon.fromStart("P1DT2H"), rules.getActivityType("IDAGrapple")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT1H42M"),
//                                        planningHorizon.fromStart("P1DT1H48M"), rules.getActivityType("ICCImages")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT1H47M50S"),
//                                        planningHorizon.fromStart("P1DT1H48M"), rules.getActivityType("ICCHeatersOff")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT2H"),
//                                        planningHorizon.fromStart("P1DT2H3M"), rules.getActivityType("IDAHeatersOff")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT2H"),
//                                        planningHorizon.fromStart("P1DT2H6M"), rules.getActivityType("IDCImages")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT2H6M"),
//                                        planningHorizon.fromStart("P1DT2H21M"), rules.getActivityType("IDCHeatersOff")));
//  }
//
//  @Test
//  public void thirdRule() {
//    var goals = new ArrayList<Goal>();
//    goals.add(rules.generateDSNVisibilityAllocationGoal().getValue());
//    goals.addAll(rules.getThirdRuleGoals().values());
//    smallProblem.setGoals(goals);
//    schedule(false);
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1D"),
//                                        planningHorizon.fromStart("P1DT8H"), rules.getActivityType("AllocateDSNStation")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1D"),
//                                        planningHorizon.fromStart("P1DT5M23S"), rules.getActivityType("XBandPrep")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1D"),
//                                        planningHorizon.fromStart("P1DT8H"), rules.getActivityType("XBandCommSched")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT5M23S"),
//                                        planningHorizon.fromStart("P1DT7H59M41S"), rules.getActivityType("XBandActive")));
//    assertTrue(TestUtility.containsActivity(plan, planningHorizon.fromStart("P1DT7H59M41S"),
//                                        planningHorizon.fromStart("P1DT8H"), rules.getActivityType("XBandCleanup")));
//  }
//}
