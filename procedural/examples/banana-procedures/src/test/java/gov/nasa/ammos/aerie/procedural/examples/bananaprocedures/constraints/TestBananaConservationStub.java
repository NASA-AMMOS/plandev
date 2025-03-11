package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyInstance;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance;
import gov.nasa.ammos.aerie.procedural.utils.StubPlan;
import gov.nasa.ammos.aerie.procedural.utils.StubSimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import kotlin.NotImplementedError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TestBananaConservationStub {
  private StubSimulationResults makeSimResults(Map<Duration,Integer> picks, Map<Duration, Double> bites) {
    return new StubSimulationResults() {
      @NotNull
      @Override
      public Instances<AnyInstance> instances(@Nullable final String type) {
        if (Objects.equals(type, "BiteBanana")) {
          return new Instances<>(bites.entrySet().stream().map(
              (e) -> new Instance<>(
                  new AnyInstance(Map.of("biteSize", SerializedValue.of(e.getValue())), SerializedValue.NULL),
                  type,
                  new ActivityInstanceId(0), null, null,
                  Interval.at(e.getKey())
              )
          ).toList());
        } else if (Objects.equals(type, "PickBanana")) {
          return new Instances<>(picks.entrySet().stream().map(
              (e) -> new Instance<>(
                  new AnyInstance(Map.of("quantity", SerializedValue.of(e.getValue())), SerializedValue.NULL),
                  type,
                  new ActivityInstanceId(0), null, null,
                  Interval.at(e.getKey())
              )
          ).toList());
        }
        throw new NotImplementedError();
      }
    };
  }
  @Test
  public void passesValidPlan() {
    final var plan = new StubPlan();
    final var simResults = makeSimResults(
        Map.of(
            Duration.ZERO, 10,
            Duration.MINUTE, 4
        ),
        Map.of(
            Duration.SECOND, 8.0,
            Duration.HOUR, 6.0
        )
    );

    final var result = new ObeyConservationOfBanana().run(plan, simResults);

    assertTrue(result.collect().isEmpty());
  }

  @Test
  public void singleViolation() {
    final var plan = new StubPlan();
    final var simResults = makeSimResults(
        Map.of(
            Duration.ZERO, 10,
            Duration.MINUTE, 5
        ),
        Map.of(
            Duration.SECOND, 13.0
        )
    );

    final var result = new ObeyConservationOfBanana().run(plan, simResults);

    assertIterableEquals(
        List.of(new Violation(Interval.betweenClosedOpen(Duration.SECOND, Duration.MINUTE), null, List.of())),
        result.collect()
    );
  }

  @Test
  public void multipleViolations() {
    final var plan = new StubPlan();
    final var simResults = makeSimResults(
        Map.of(
            Duration.ZERO, 10,
            Duration.MINUTE, 5,
            Duration.minutes(10), 10,
            Duration.minutes(20), 5
        ),
        Map.of(
            Duration.SECOND, 17.0,
            Duration.minutes(11), 9.0
        )
    );

    final var result = new ObeyConservationOfBanana().run(plan, simResults);

    assertIterableEquals(
        List.of(
            new Violation(Interval.betweenClosedOpen(Duration.SECOND, Duration.minutes(10)), null, List.of()),
            new Violation(Interval.betweenClosedOpen(Duration.minutes(11), Duration.minutes(20)), null, List.of())
        ),
        result.collect()
    );
  }
}
