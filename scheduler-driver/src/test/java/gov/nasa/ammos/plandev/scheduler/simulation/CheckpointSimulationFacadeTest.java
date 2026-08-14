package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.merlin.driver.SimulationEngineConfiguration;
import gov.nasa.jpl.aerie.merlin.framework.ThreadedTask;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.SimulationUtility;
import gov.nasa.jpl.aerie.scheduler.TimeUtility;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CheckpointSimulationFacadeTest {
  private static final DirectiveIdGenerator idGenerator = new DirectiveIdGenerator(0);
  private SimulationFacade newSimulationFacade;
  private final static PlanningHorizon H = new PlanningHorizon(TimeUtility.fromDOY("2025-001T00:00:00.000"), TimeUtility.fromDOY("2025-005T00:00:00.000"));
  private Map<String, ActivityType> activityTypes;
  private final static Duration t0 = H.getStartAerie();
  private final static Duration d1hr = Duration.of(1, HOUR);
  private final static Duration t1hr = t0.plus(d1hr);
  private final static Duration t2hr = t0.plus(d1hr.times(2));
  private static PlanInMemory makePlanA012(Map<String, ActivityType> activityTypeMap) {
    final var plan = new PlanInMemory();
    final var actTypeA = activityTypeMap.get("BasicActivity");
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t0, null, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t1hr, null, null, true));
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t2hr, null, null, true));
    return plan;
  }
  @BeforeEach
  public void before(){
    ThreadedTask.CACHE_READS = true;
    final var fooMissionModel = SimulationUtility.getFooMissionModel();
    activityTypes = new HashMap<>();
    for(var taskType : fooMissionModel.getDirectiveTypes().directiveTypes().entrySet()){
      activityTypes.put(taskType.getKey(), new ActivityType(taskType.getKey(), taskType.getValue(), SimulationUtility.getFooSchedulerModel().getDurationTypes().get(taskType.getKey())));
    }
    newSimulationFacade = new CheckpointSimulationFacade(
        fooMissionModel,
        SimulationUtility.getFooSchedulerModel(),
        new InMemoryCachedEngineStore(10),
        H,
        new SimulationEngineConfiguration(Map.of(), Instant.EPOCH, new MissionModelId(1)),
        () -> false);
    newSimulationFacade.addActivityTypes(activityTypes.values());
  }

  /**
   * This is to check that one of the simulation interfaces, the one simulating a plan until a given time, is actually stopping
   * at the given time. It does that by checking that calling the simulation did not update the activity's duration.
   */
  @Test
  public void simulateUntilTime() throws SimulationFacade.SimulationException, SchedulingInterruptedException {
    final var plan = makePlanA012(activityTypes);
    newSimulationFacade.simulateNoResults(plan, t2hr);
    //we are stopping at 2hr, at the start of the last activity so it will not have a duration in the plan
    assertNull(plan.getActivities().stream().filter(a -> a.startOffset().equals(t2hr)).findFirst().get().duration());
  }

  /**
   * Simulating the same plan on a smaller horizon leads to re-using the simulation data
   */
  @Test
  public void noNeedForResimulation() throws SimulationFacade.SimulationException, SchedulingInterruptedException {
    final var plan = makePlanA012(activityTypes);
    final var ret = newSimulationFacade.simulateWithResults(plan, t2hr);
    final var ret2 = newSimulationFacade.simulateWithResults(plan, t1hr);
    SimulationResultsComparisonUtils.assertEqualsSimulationResults(ret.driverResults(), ret2.driverResults());
  }

  /**
   * Simulating the same plan on a smaller horizon via a different request (no-results vs with-results) leads to the same
   * simulation data
   */
  @Test
  public void simulationResultsTest() throws SchedulingInterruptedException, SimulationFacade.SimulationException {
    final var plan = makePlanA012(activityTypes);
    final var simResults = newSimulationFacade.simulateNoResultsAllActivities(plan).computeResults();
    final var simResults2 = newSimulationFacade.simulateWithResults(plan, t1hr);
    SimulationResultsComparisonUtils.assertEqualsSimulationResults(simResults, simResults2.driverResults());
  }

  /**
   * Tests that the simulation stops at the end of the planning horizon even if the plan we are trying to simulate
   * is supposed to last longer.
   */
  @Test
  public void testStopsAtEndOfPlanningHorizon()
  throws SchedulingInterruptedException, SimulationFacade.SimulationException
  {
    final var plan = new PlanInMemory();
    final var actTypeA = activityTypes.get("ControllableDurationActivity");
    plan.add(SchedulingActivity.of(idGenerator.next(), actTypeA, t0, HOUR.times(200), null, true));
    final var results = newSimulationFacade.simulateNoResultsAllActivities(plan).computeResults();
    assertEquals(H.getEndAerie(), results.duration);
    assert(results.unfinishedActivities.size() == 1);
  }

}
