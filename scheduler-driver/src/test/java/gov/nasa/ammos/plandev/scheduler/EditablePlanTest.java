package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerPlanEditAdapter;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerToProcedurePlanAdapter;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class EditablePlanTest {

  MissionModel<?> missionModel;
  Problem problem;
  SimulationFacade facade;
  EditablePlan plan;

  private static final Instant start = TestUtility.timeFromEpochMillis(0);
  private static final Instant end = TestUtility.timeFromEpochDays(1);

  private static final PlanningHorizon horizon = new PlanningHorizon(start, end);

  @BeforeEach
  public void setUp() {
    missionModel = SimulationUtility.getBananaMissionModel();
    final var schedulerModel = SimulationUtility.getBananaSchedulerModel();
    facade = new CheckpointSimulationFacade(horizon, missionModel, schedulerModel);
    problem = new Problem(missionModel, horizon, facade, schedulerModel);
    final var editAdapter = new SchedulerPlanEditAdapter(
        missionModel,
        new DirectiveIdGenerator(0),
        new SchedulerToProcedurePlanAdapter(
            new PlanInMemory(

            ),
            horizon,
            Map.of(), Map.of(), Map.of()
        ),
        facade,
        problem::getActivityType
    );
    plan = new DefaultEditablePlanDriver(editAdapter);
  }

  @AfterEach
  public void tearDown() {
    missionModel = null;
    problem = null;
    facade = null;
    plan = null;
  }

  @Test
  public void activityCreation() {
    plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    assertEquals(1, plan.directives().collect().size());
    assertEquals(Map.of("biteSize", SerializedValue.of(1)), plan.directives().collect().getFirst().inner.arguments);
  }

  @Test
  public void activityDeletion() {
    final var id1 = plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );
    final var id2 = plan.create(
        "GrowBanana",
        new DirectiveStart.Absolute(Duration.HOUR),
        Map.of("growingDuration", SerializedValue.of(1), "quantity", SerializedValue.of(1))
    );

    final Supplier<Set<ActivityDirectiveId>> idSet =
        () -> plan.directives().collect().stream().map($ -> $.id).collect(Collectors.toSet());

    plan.commit();

    assertEquals(Set.of(id1, id2), idSet.get());

    plan.delete(id1);

    assertEquals(Set.of(id2), idSet.get());

    plan.delete(id2);

    assertEquals(Set.of(), idSet.get());
  }

  @Test
  public void simResultMarkedStale() {
    plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    final var simResults = plan.simulate();

    assertFalse(simResults.isStale());

    plan.create(
        "GrowBanana",
        new DirectiveStart.Absolute(Duration.HOUR),
        Map.of(
            "growingDuration", SerializedValue.of(10),
            "quantity", SerializedValue.of(1)
        )
    );

    assertTrue(simResults.isStale());
  }

  @Test
  public void simResultMarkedStaleAfterDelete() {
    final var id = plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    final var simResults = plan.simulate();

    assertFalse(simResults.isStale());

    plan.delete(id);

    assertTrue(simResults.isStale());
  }

  @Test
  public void simResultMarkedNotStaleAfterRollback_CommitThenSimulate() {
    plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    plan.commit();
    final var simResults = plan.simulate();

    assertFalse(simResults.isStale());

    plan.create(
        "GrowBanana",
        new DirectiveStart.Absolute(Duration.HOUR),
        Map.of(
            "growingDuration", SerializedValue.of(10),
            "quantity", SerializedValue.of(1)
        )
    );

    assertTrue(simResults.isStale());

    plan.rollback();

    assertFalse(simResults.isStale());
  }

  @Test
  public void simResultMarkedNotStaleAfterRollback_SimulateThenCommit() {
    final var id = plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    final var simResults = plan.simulate();
    plan.commit();

    assertFalse(simResults.isStale());

    plan.delete(id);

    assertTrue(simResults.isStale());

    plan.rollback();

    assertFalse(simResults.isStale());
  }

  @Test
  void simulationInputDirectivesDontChange() {
    plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    final var expectedDirectives = plan.directives();
    final var simResults = plan.simulate();

    plan.create(
        "GrowBanana",
        new DirectiveStart.Absolute(Duration.HOUR),
        Map.of(
            "growingDuration", SerializedValue.of(10000),
            "quantity", SerializedValue.of(1)
        )
    );

    assertIterableEquals(expectedDirectives, simResults.inputDirectives());
    assertNotEquals(plan.directives(), simResults.inputDirectives());

    plan.commit();

    assertIterableEquals(expectedDirectives, simResults.inputDirectives());
    assertNotEquals(plan.directives(), simResults.inputDirectives());

    assertIterableEquals(plan.directives(), plan.simulate().inputDirectives());
  }
}
