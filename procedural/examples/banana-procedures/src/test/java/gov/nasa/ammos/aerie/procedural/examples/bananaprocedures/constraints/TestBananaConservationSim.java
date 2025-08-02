package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.utils.TypeUtilsEditablePlanAdapter;
import gov.nasa.ammos.aerie.procedural.utils.TypeUtilsPlanAdapter;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/**
 * Example test for procedural constraints, using real simulation.
 * General workflow:
 * 1. Create a {@link SimulationUtility} instance.
 * 2. Load the mission model using the sim utility.
 * 3. Create a new empty plan. You'll need to use a couple adapters, see {@link TestBananaConservationSim.beforeEach}.
 *    for an example.
 * 4. Add activities and simulate using the {@link EditablePlan} interface.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestBananaConservationSim {
  private MissionModel<?> model;
  private SimulationUtility simUtility;
  private EditablePlan plan;

  @BeforeAll
  void beforeAll() throws MissionModelLoader.MissionModelLoadException {
    simUtility = new SimulationUtility();
    model = SimulationUtility.instantiateMissionModel(
        Path.of("../../../examples/banananation/build/libs/banananation.jar"),
        Instant.EPOCH,
        Map.of(
            "initialDataPath", SerializedValue.of("../../../build.gradle")
        )
    );
  }

  @AfterAll
  void afterAll() {
    simUtility.close();
  }

  @BeforeEach
  void beforeEach() {
    plan = new DefaultEditablePlanDriver(
        new TypeUtilsEditablePlanAdapter(
            new TypeUtilsPlanAdapter(
                new Plan("test plan", new Timestamp(Instant.EPOCH), new Timestamp(Instant.EPOCH.plusSeconds(60 * 60 * 24)), Map.of(), Map.of())
            ),
            simUtility,
            model
        )
    );
  }

  @Test
  final void passesValidPlan() {
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.SECOND), Map.of("quantity", SerializedValue.of(15)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.MINUTE), Map.of("biteSize", SerializedValue.of(10)));

    final var violations = new ObeyConservationOfBanana().run(plan, plan.simulate());

    assertIterableEquals(
        List.of(),
        violations.collect()
    );
  }

  @Test
  final void singleViolation() {
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.SECOND), Map.of("quantity", SerializedValue.of(15)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.MINUTE), Map.of("biteSize", SerializedValue.of(20)));
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.HOUR), Map.of("quantity", SerializedValue.of(15)));

    final var violations = new ObeyConservationOfBanana().run(plan, plan.simulate());

    assertIterableEquals(
        List.of(
            new Violation(Interval.betweenClosedOpen(Duration.MINUTE, Duration.HOUR), null, List.of())
        ),
        violations.collect()
    );
  }

  @Test
  final void multipleViolations() {
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.SECOND), Map.of("quantity", SerializedValue.of(15)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.MINUTE), Map.of("biteSize", SerializedValue.of(20)));
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.minutes(2)), Map.of("quantity", SerializedValue.of(15)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.minutes(3)), Map.of("biteSize", SerializedValue.of(20)));
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.minutes(4)), Map.of("quantity", SerializedValue.of(15)));

    final var violations = new ObeyConservationOfBanana().run(plan, plan.simulate());

    assertIterableEquals(
        List.of(
            new Violation(Interval.betweenClosedOpen(Duration.MINUTE, Duration.minutes(2)), null, List.of()),
            new Violation(Interval.betweenClosedOpen(Duration.minutes(3), Duration.minutes(4)), null, List.of())
        ),
        violations.collect()
    );
  }
}
