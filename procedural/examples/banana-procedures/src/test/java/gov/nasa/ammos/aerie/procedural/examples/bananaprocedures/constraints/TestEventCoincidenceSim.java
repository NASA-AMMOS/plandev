package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
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
 * 3. Create a new empty plan. You'll need to use a couple adapters, see {@link TestEventCoincidenceSim.beforeEach}.
 *    for an example.
 * 4. Add activities and simulate using the {@link EditablePlan} interface.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestEventCoincidenceSim {
  private MissionModel<?> model;
  private SimulationUtility simUtility;
  private EditablePlan plan;

  private ExternalEvents getExternalEvents() {
    return new ExternalEvents(List.of(
        // one at t = 0
        new ExternalEvent(
            "1",
            "EventType",
            new ExternalSource("BasicSource", "DG", Map.of()),
            Map.of(),
            Interval.between(Duration.ZERO, Duration.SECOND.times(2))
        ),

        // one at t = 1s
        new ExternalEvent(
            "2",
            "EventType",
            new ExternalSource("BasicSource", "DG", Map.of()),
            Map.of(),
            Interval.between(Duration.SECOND, Duration.MINUTE)
        ),

        // one at t = 1m
        new ExternalEvent(
            "3",
            "EventType",
            new ExternalSource("BasicSource", "DG", Map.of()),
            Map.of(),
            Interval.between(Duration.MINUTE, Duration.MINUTE.times(2))
        )
    ));
  }

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
                new Plan("test plan", new Timestamp(Instant.EPOCH), new Timestamp(Instant.EPOCH.plusSeconds(60 * 60 * 24)), Map.of(), Map.of()),
                getExternalEvents()
            ),
            simUtility,
            model
        )
    );
  }

  // three with correct activity
  @Test
  final void passesValidPlan() {
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.ZERO), Map.of("biteSize", SerializedValue.of(10)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.SECOND), Map.of("biteSize", SerializedValue.of(1)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.MINUTE), Map.of("biteSize", SerializedValue.of(4)));

    final var violations = new EventCoincidence().run(plan, plan.simulate());

    assertIterableEquals(
        List.of(),
        violations.collect()
    );
  }

  // one where there is event with no activity
  // one where there is event with wrong activity
  // one with correct activity
  @Test
  final void constraintViolated() {
    plan.create("PickBanana", new DirectiveStart.Absolute(Duration.SECOND), Map.of("quantity", SerializedValue.of(5)));
    plan.create("BiteBanana", new DirectiveStart.Absolute(Duration.MINUTE), Map.of("biteSize", SerializedValue.of(13.0)));

    final var violations = new EventCoincidence().run(plan, plan.simulate());

    assertIterableEquals(
        List.of(
            new Violation(Interval.between(Duration.ZERO, Duration.SECOND.times(2)), null, List.of()),
            new Violation(Interval.between(Duration.SECOND, Duration.MINUTE), null, List.of())
        ),
        violations.collect()
    );
  }
}
