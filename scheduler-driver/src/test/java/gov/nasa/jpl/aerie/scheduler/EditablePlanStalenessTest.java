package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.PlanInMemory;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.plan.InMemoryEditablePlan;
import gov.nasa.jpl.aerie.scheduler.plan.SchedulerToProcedurePlanAdapter;
import gov.nasa.jpl.aerie.scheduler.simulation.CheckpointSimulationFacade;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EditablePlanStalenessTest {

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
    plan = new InMemoryEditablePlan(
        new DirectiveIdGenerator(0),
        new SchedulerToProcedurePlanAdapter(
            new PlanInMemory(),
            horizon,
            Map.of(), Map.of(), Map.of()
        ),
        facade,
        problem::getActivityType
    );
  }

  @AfterEach
  public void tearDown() {
    missionModel = null;
    problem = null;
    facade = null;
    plan = null;
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
    plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    final var simResults = plan.simulate();
    plan.commit();

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
}
