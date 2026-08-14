package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.timeline.TemporalEventSource;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ActivityInstanceTest {
  @Test
  public void testUnspecifiedArgInSimulatedActivity() {
    final var schedule = SimulationUtility.buildSchedule(
        Pair.of(
            duration(0, SECONDS),
            new SerializedActivity("PeelBanana", Map.of())),
        Pair.of(
            duration(0, SECONDS),
            new SerializedActivity("GrowBanana", Map.of(
                "quantity", SerializedValue.of(1),
                "growingDuration", SerializedValue.of(Duration.SECOND.times(2).in(Duration.MICROSECONDS))
            ))));

    final var simDuration = duration(1, SECOND);

    final var simulationResults = SimulationUtility.simulate(schedule, simDuration);

    assertEquals(1, simulationResults.simulatedActivities.size());
    simulationResults.simulatedActivities.forEach( (id, act) -> {
        assertEquals(1, act.arguments().size());
        assertTrue(act.arguments().containsKey("peelDirection"));
    });

    assertEquals(1, simulationResults.unfinishedActivities.size());
    simulationResults.unfinishedActivities.forEach( (id, act) -> {
      assertEquals(2, act.arguments().size());
      assertTrue(act.arguments().containsKey("quantity"));
      assertTrue(act.arguments().containsKey("growingDuration"));
    });
  }

  /** This test is a response to not accounting for all Task ExecutionStates
   * when collecting activities into the results object. This indirectly tests that portion
   * of {@link gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine#computeResults(
   * SimulationEngine, Instant, Duration, Topic, TemporalEventSource, MissionModel) computeResults()}
   *
   * The schedule in this test, results produces Tasks in all three of the states,
   * {@link gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine.ExecutionState.AwaitingChildren AwaitingChildren},
   * {@link gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine.ExecutionState.InProgress InProgress}, and
   * {@link gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine.ExecutionState.Terminated Terminated}.
   */
  @Test
  public void testCollectAllActivitiesInResults() {
    final var schedule = SimulationUtility.buildSchedule(
        Pair.of(
            duration(0, SECONDS),
            new SerializedActivity("PeelBanana", Map.of())),
        Pair.of(
            duration(0, SECONDS),
            new SerializedActivity("GrowBanana", Map.of(
                "quantity", SerializedValue.of(1),
                "growingDuration", SerializedValue.of(Duration.SECOND.times(3).in(Duration.MICROSECONDS))
            ))),
        Pair.of(
            duration(0, SECONDS),
            new SerializedActivity("DecomposingSpawnParent", Map.of())));

    final var simDuration = duration(2, SECONDS);

    final var simulationResults = SimulationUtility.simulate(schedule, simDuration);

    assertEquals(2, simulationResults.simulatedActivities.size());

    var simulatedActivityTypes = new HashSet<String>();
    simulationResults.simulatedActivities.forEach( (id, act) -> simulatedActivityTypes.add(act.type()));
    Collection<String> expectedSimulated = new HashSet<>(
        Arrays.asList("PeelBanana", "DecomposingSpawnChild"));

    assertEquals(simulatedActivityTypes, expectedSimulated);

    assertEquals(3, simulationResults.unfinishedActivities.size());

    var unfinishedActivityTypes = new HashSet<String>();
    simulationResults.unfinishedActivities.forEach( (id, act) -> unfinishedActivityTypes.add(act.type()));

    Collection<String> expectedUnfinished = new HashSet<>(
        Arrays.asList("GrowBanana", "DecomposingSpawnChild", "DecomposingSpawnParent"));
    assertEquals(unfinishedActivityTypes, expectedUnfinished);
  }
}
