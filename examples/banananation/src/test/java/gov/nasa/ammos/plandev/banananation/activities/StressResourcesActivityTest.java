package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.banananation.SimulationUtility;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.SerializedActivity;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of StressResources is that the number of segments it produces is predictable, so
 * that someone testing timeline performance at "500k segments" is actually looking at 500k segments.
 * These tests pin that contract.
 */
public class StressResourcesActivityTest {
  private static SerializedActivity stressActivity(
      final int segmentCount,
      final int realResourceCount,
      final int discreteResourceCount,
      final Duration duration) {
    return new SerializedActivity(
        "StressResources",
        Map.of(
            "segmentCount", SerializedValue.of(segmentCount),
            "realResourceCount", SerializedValue.of(realResourceCount),
            "discreteResourceCount", SerializedValue.of(discreteResourceCount),
            "duration", SerializedValue.of(duration.in(Duration.MICROSECONDS))));
  }

  @Test
  public void producesRequestedSegmentCountOnRealResource() {
    final var segmentCount = 500;
    final var results = SimulationUtility.simulate(
        SimulationUtility.buildSchedule(
            Pair.of(Duration.ZERO, stressActivity(segmentCount, 1, 0, Duration.of(1, Duration.HOUR)))),
        Duration.of(2, Duration.HOUR));

    final var profile = results.realProfiles.get("/stress/real/0");
    assertTrue(profile != null, "expected /stress/real/0 to be registered and produced");

    // One segment per emitted rate change. The engine may coalesce the trailing segment with the
    // simulation's end, so allow the count to be off by one rather than pinning it exactly.
    final var actual = profile.segments().size();
    assertTrue(
        Math.abs(actual - segmentCount) <= 1,
        "expected ~%d segments on /stress/real/0 but got %d".formatted(segmentCount, actual));
  }

  @Test
  public void drivesOnlyTheRequestedNumberOfResources() {
    final var results = SimulationUtility.simulate(
        SimulationUtility.buildSchedule(
            Pair.of(Duration.ZERO, stressActivity(100, 2, 0, Duration.of(1, Duration.HOUR)))),
        Duration.of(2, Duration.HOUR));

    // Driven resources get many segments; untouched ones stay flat.
    assertTrue(results.realProfiles.get("/stress/real/0").segments().size() > 50);
    assertTrue(results.realProfiles.get("/stress/real/1").segments().size() > 50);
    for (int i = 2; i < Mission.STRESS_RESOURCE_POOL_SIZE; i++) {
      final var idle = results.realProfiles.get("/stress/real/" + i);
      assertEquals(1, idle.segments().size(), "/stress/real/%d should be idle".formatted(i));
    }
  }

  @Test
  public void discreteResourcesProduceCoalescableRuns() {
    final var segmentCount = 210;
    final var results = SimulationUtility.simulate(
        SimulationUtility.buildSchedule(
            Pair.of(Duration.ZERO, stressActivity(segmentCount, 0, 1, Duration.of(1, Duration.HOUR)))),
        Duration.of(2, Duration.HOUR));

    final var profile = results.discreteProfiles.get("/stress/discrete/0");
    assertTrue(profile != null, "expected /stress/discrete/0 to be registered and produced");

    // Setting a Register to its current value still emits a segment, so segment count tracks the
    // requested count -- which is the knob users turn for performance testing.
    final var actual = profile.segments().size();
    assertTrue(
        Math.abs(actual - segmentCount) <= 1,
        "expected ~%d segments on /stress/discrete/0 but got %d".formatted(segmentCount, actual));

    // Labels only change every 7 steps, so those segments form ~30 runs of equal values. That is
    // deliberate: the x-range layer coalesces equal-label runs into single boxes, so this shape
    // exercises the coalescing path instead of degenerating into one box per segment.
    var runs = 0;
    SerializedValue previous = null;
    for (final var segment : profile.segments()) {
      if (previous == null || !previous.equals(segment.dynamics())) {
        runs++;
      }
      previous = segment.dynamics();
    }
    assertTrue(runs >= 25 && runs <= 40, "expected ~30 equal-value runs but got %d".formatted(runs));
    assertTrue(runs < actual / 4, "runs should be far fewer than segments so coalescing is meaningful");
  }

  @Test
  public void rejectsInvalidConfiguration() {
    final var duration = Duration.of(1, Duration.HOUR);

    assertTrue(!new StressResourcesActivity(0, 1, 0, duration).validateSegmentCount());
    assertTrue(!new StressResourcesActivity(1, Mission.STRESS_RESOURCE_POOL_SIZE + 1, 0, duration)
        .validateRealResourceCount());
    assertTrue(!new StressResourcesActivity(1, 0, 0, duration).validateSomeResourceSelected());
    assertTrue(!new StressResourcesActivity(2, 1, 0, Duration.of(1, Duration.MICROSECOND))
        .validateDuration());
  }

  @Test
  public void idleWhenNoStressActivityIsScheduled() {
    final var results = SimulationUtility.simulate(
        SimulationUtility.buildSchedule(
            Pair.of(Duration.ZERO, new SerializedActivity("BiteBanana", Map.of()))),
        Duration.of(1, Duration.HOUR));

    // Stress resources are always registered, so they must cost essentially nothing when unused.
    for (int i = 0; i < Mission.STRESS_RESOURCE_POOL_SIZE; i++) {
      assertEquals(1, results.realProfiles.get("/stress/real/" + i).segments().size());
      assertEquals(1, results.discreteProfiles.get("/stress/discrete/" + i).segments().size());
    }
  }
}
