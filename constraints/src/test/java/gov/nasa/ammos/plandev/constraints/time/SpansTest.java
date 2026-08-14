package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.constraints.Assertions.assertEquivalent;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.at;
import static gov.nasa.jpl.aerie.constraints.time.Interval.interval;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

public class SpansTest {
  @Test
  public void addEmpty() {
    final var spans = new Spans();
    spans.add(Interval.EMPTY);

    final var expected = new Spans();

    assertEquivalent(spans, expected);
  }

  @Test
  public void addOpenPoint() {
    final var spans = new Spans();
    spans.add(interval(1, Exclusive, 1, Exclusive, MICROSECONDS));

    final var expected = new Spans();

    assertEquivalent(spans, expected);
  }

  @Test
  public void doNotCoalesceAdjacent() {
    final var spans = new Spans();
    spans.add(interval(0, Inclusive, 1, Exclusive, MICROSECONDS));
    spans.add(interval(1, Inclusive, 2, Inclusive, MICROSECONDS));

    final var expected = List.of(
        Segment.of(interval(0, Inclusive, 1, Exclusive, MICROSECONDS), Optional.empty()),
        Segment.of(interval(1, Inclusive, 2, Inclusive, MICROSECONDS), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void doNotCoalesceOverlap() {
    final var spans = new Spans(
        interval(0, Inclusive, 1, Inclusive, SECONDS),
        interval(500, Inclusive, 2000, Inclusive, MILLISECONDS)
    );

    final var expected = List.of(
        Segment.of(interval(0, Inclusive, 1, Inclusive, SECONDS), Optional.empty()),
        Segment.of(interval(500, Inclusive, 2000, Inclusive, MILLISECONDS), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void filter() {
    final var spans = new Spans(
        interval(0, 1, SECONDS),
        interval(0, 2, SECONDS),
        interval(0, 3, SECONDS)
    ).filter($ -> $.duration().shorterThan(Duration.of(2, SECONDS)) || $.duration().longerThan(Duration.of(2, SECONDS)));

    final var expected = List.of(
        Segment.of(interval(0, 1, SECONDS), Optional.empty()),
        Segment.of(interval(0, 3, SECONDS), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void map() {
    final var spans = new Spans(
        interval(0, 2, SECONDS),
        interval(0, 3, SECONDS)
    ).map($ -> interval($.start, $.start.plus(SECOND)));

    final var expected = List.of(
        Segment.of(interval(0, 1, SECOND), Optional.empty()),
        Segment.of(interval(0, 1, SECOND), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void mapFiltersEmpty() {
    final var spans = new Spans(
        interval(0, Inclusive, 1, Exclusive, SECONDS),
        interval(0, 3, SECONDS)
    ).map($ -> interval($.start, $.startInclusivity, $.end.minus(SECOND), $.endInclusivity));

    final var expected = List.of(
        Segment.of(interval(0, 2, SECOND), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void flatMap() {
    final var spans = new Spans(
        interval(0, 1, SECONDS),
        interval(0, 3, SECONDS)
    ).flatMap($ -> {
      if ($.duration().noLongerThan(SECOND)) {
        return Stream.of();
      } else {
        return Stream.of(
            interval($.end, $.end),
            interval($.end, $.end.plus(SECOND))
        );
      }
    });

    final var expected = List.of(
        Segment.of(interval(3, 3, SECONDS), Optional.empty()),
        Segment.of(interval(3, 4, SECONDS), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void flatMapFiltersEmpty() {
    final var spans = new Spans(
        interval(0, Inclusive, 1, Exclusive, SECONDS),
        interval(0, 3, SECONDS)
    ).flatMap($ -> Stream.of(interval($.start, $.startInclusivity, $.end.minus(SECOND), $.endInclusivity)));

    final var expected = List.of(
        Segment.of(interval(0, 2, SECOND), Optional.empty())
    );

    assertIterableEquals(expected, spans);
  }

  @Test
  public void intoWindows() {
    final var intervals = new Spans(
        interval(0, 2, SECONDS),
        interval(1, 3, SECONDS),
        interval(5, 5, SECONDS)
    ).intoWindows();

    final var expected = List.of(
        Segment.of(interval(Duration.MIN_VALUE, Inclusive, Duration.ZERO, Exclusive), false),
        Segment.of(interval(0, 3, SECONDS), true),
        Segment.of(interval(3, Exclusive, 5, Exclusive, SECONDS), false),
        Segment.of(at(5, SECONDS), true),
        Segment.of(interval(Duration.of(5, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), false)
    );

    assertIterableEquals(expected, intervals);
  }

  @Test
  public void accumulatedDuration() {
    final var acc = new Spans(
        interval(0, 2, SECONDS),
        interval(1, 3, SECONDS),
        at(4, SECONDS),
        interval(5, Exclusive, 6, Inclusive, SECONDS)
    ).accumulatedDuration(Duration.SECOND);

    final var expected = new LinearProfile(
        Segment.of(interval(Duration.MIN_VALUE, Inclusive, Duration.ZERO, Exclusive), new LinearEquation(Duration.ZERO, 0, 0)),
        Segment.of(interval(0, Inclusive, 1, Exclusive, SECONDS), new LinearEquation(Duration.ZERO, 0, 1)),
        Segment.of(interval(1, 2, SECONDS), new LinearEquation(Duration.SECOND, 1, 2)),
        Segment.of(interval(2, Exclusive, 3, Inclusive, SECONDS), new LinearEquation(Duration.of(2, SECOND), 3, 1)),
        Segment.of(interval(3, Exclusive, 5, Inclusive, SECONDS), new LinearEquation(Duration.ZERO, 4, 0)),
        Segment.of(interval(5, Exclusive, 6, Inclusive, SECONDS), new LinearEquation(Duration.of(5, SECOND), 4, 1)),
        Segment.of(interval(Duration.of(6, SECOND), Exclusive, Duration.MAX_VALUE, Inclusive), new LinearEquation(Duration.ZERO, 5, 0))
    );

    assertIterableEquals(expected, acc);
  }

  @Test
  public void testIntersectWindows() {
    final var intersection = new Spans(interval(0, 2, SECONDS)).intersectWith(
        new Windows(false).set(interval(1, 10, SECONDS), true)
    );

    final var expected = new Spans(interval(1,2, SECONDS));

    assertIterableEquals(expected, intersection);
  }
}
