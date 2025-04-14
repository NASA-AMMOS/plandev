package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyInstance;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance;
import gov.nasa.ammos.aerie.procedural.utils.StubPlan;
import gov.nasa.ammos.aerie.procedural.utils.StubSimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example class for testing constraints with stubbed simulation results.
 * 1. Start with {@link StubPlan} and {@link StubSimulationResults}. These classes
 *    throw exceptions on all methods.
 * 2. Override the methods that you need to provide specific results. You'll need to
 *    manually create timelines for instances and/or resource profiles.
 */
public class TestEventCoincidenceStub {
  private StubSimulationResults makeSimResults(Map<Duration,Integer> picks, Map<Duration, Double> bites) {
    return new StubSimulationResults() {
      @NotNull
      @Override
      public Instances<AnyInstance> instances() {
        return instances("");
      }

      @NotNull
      @Override
      public Instances<AnyInstance> instances(@Nullable final String type) {
        if (Objects.equals(type, "BiteBanana")) {
          return new Instances<>(bites.entrySet().stream().map(
              (e) -> new Instance<>(
                  new AnyInstance(Map.of("biteSize", SerializedValue.of(e.getValue())), SerializedValue.NULL),
                  "BiteBanana",
                  new ActivityInstanceId(0), null, null,
                  Interval.at(e.getKey())
              )
          ).toList());
        } else if (Objects.equals(type, "PickBanana")) {
          return new Instances<>(picks.entrySet().stream().map(
              (e) -> new Instance<>(
                  new AnyInstance(Map.of("quantity", SerializedValue.of(e.getValue())), SerializedValue.NULL),
                  "PickBanana",
                  new ActivityInstanceId(0), null, null,
                  Interval.at(e.getKey())
              )
          ).toList());
        }
        return new Instances<>(
            Stream.concat(
                bites.entrySet().stream().map(
                    (e) -> {
                        assert type != null;
                        return new Instance<>(
                            new AnyInstance(Map.of("biteSize", SerializedValue.of(e.getValue())), SerializedValue.NULL),
                            "BiteBanana",
                            new ActivityInstanceId(0), null, null,
                            Interval.at(e.getKey())
                        );
                    }
                ),
                picks.entrySet().stream().map(
                   (e) -> {
                       assert type != null;
                       return new Instance<>(
                           new AnyInstance(Map.of("quantity", SerializedValue.of(e.getValue())), SerializedValue.NULL),
                           "PickBanana",
                           new ActivityInstanceId(0), null, null,
                           Interval.at(e.getKey())
                       );
                   }
               )
            ).collect(Collectors.toList())
        );
      }
    };
  }

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

  // three with correct activity
  @Test
  public void passesValidPlan() {
    final var plan = new StubPlan(getExternalEvents());
    final var simResults = makeSimResults(
        Map.of(
            Duration.ZERO, 10,
            Duration.SECOND, 1,
            Duration.MINUTE, 4
        ),
        Map.of()
    );

    final var result = new EventCoincidence().run(plan, simResults);
    assertTrue(result.collect().isEmpty());
  }

  // one where there is event with no activity
  // one where there is event with wrong activity
  // one with correct activity
  @Test
  public void constraintViolated() {
    final var plan = new StubPlan(getExternalEvents());
    final var simResults = makeSimResults(
        Map.of(
            // incorrect
            Duration.SECOND, 5
        ),
        Map.of(
            // correct
            Duration.MINUTE, 13.0
        )
    );

    final var result = new EventCoincidence().run(plan, simResults);

    assertIterableEquals(
        List.of(
            // no activity
            new Violation(Interval.between(Duration.ZERO, Duration.SECOND.times(2)), null, List.of()),

            // wrong activity
            new Violation(Interval.between(Duration.SECOND, Duration.MINUTE), null, List.of())
        ),
        result.collect()
    );
  }
}
