package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.And;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteResource;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteValue;
import gov.nasa.jpl.aerie.constraints.tree.Equal;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.constraints.TimeRangeExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.filters.FilterSequenceMaxGapAfter;
import gov.nasa.jpl.aerie.scheduler.constraints.filters.FilterSequenceMinGapAfter;
import gov.nasa.jpl.aerie.scheduler.constraints.filters.Filters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.interval;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestFilters {

  @Test
  public void testLatchFilters() {
    final var horizon = Interval.between(Duration.of(0, Duration.SECONDS), Duration.of(50, Duration.SECONDS));
    final var horizonW = new Windows(horizon, true);
    final var simResults = new SimulationResults(
        Instant.EPOCH,
        Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of(
            "smallState1", smallState1(horizon),
            "smallState2", smallState2(horizon)
        )
    );

    final var ste = new Equal<>(new DiscreteResource("smallState1"), new DiscreteValue(SerializedValue.of(true)));
    final var ste2 = new And(
        new Equal<>(new DiscreteResource("smallState1"), new DiscreteValue(SerializedValue.of(true))),
        new Equal<>(new DiscreteResource("smallState2"), new DiscreteValue(SerializedValue.of(true)))
    );

    final var tre = new TimeRangeExpression.Builder()
        .from(ste)
        .name("withinEach")
        .build();

    final var filter = new Filters.LatchingBuilder()
        .withinEach(tre)
        .filterFirstBy(Filters.minDuration(Duration.of(3, Duration.SECONDS)))
        .thenFilterBy(Filters.minDuration(Duration.of(4, Duration.SECONDS)))
        .build();

    final var tre2 = new TimeRangeExpression.Builder()
        .name("tre2")
        .from(ste2)
        .thenFilter(filter)
        .build();

    final var res = tre2.computeRange(simResults, null, horizonW);

    final var expected = new Windows(interval(0, Inclusive, 20, Exclusive, SECONDS), false)
        .set(List.of(
            interval(3, Inclusive, 6, Exclusive, SECONDS),
            interval(11, Inclusive, 15, Exclusive, SECONDS)
        ), true);

    assertEquals(expected, res);
  }

  public DiscreteProfile smallState1(final Interval horizon) {
    return new DiscreteProfile(List.of(
        Segment.of(Interval.between(horizon.start.in(SECONDS), Inclusive, 20, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(20, Inclusive, 25, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(25, Inclusive, horizon.end.in(SECONDS), Exclusive, SECONDS), SerializedValue.of(true))
    ));
  }

  public DiscreteProfile smallState2(final Interval horizon) {
    return new DiscreteProfile(List.of(
        Segment.of(Interval.between(horizon.start.in(SECONDS), Inclusive, 2, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(2, Inclusive, 3, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(3, Inclusive, 6, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(6, Inclusive, 7, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(7, Inclusive, 10, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(10, Inclusive, 11, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(11, Inclusive, 15, Exclusive, SECONDS), SerializedValue.of(true)),
        Segment.of(Interval.between(15, Inclusive, 22, Exclusive, SECONDS), SerializedValue.of(false)),
        Segment.of(Interval.between(22, Inclusive, horizon.end.in(SECONDS), Exclusive, SECONDS), SerializedValue.of(false))
        ));
  }

  @Test
  public void testMaxGapAfter() {
    FilterSequenceMaxGapAfter fsm = new FilterSequenceMaxGapAfter(Duration.of(1, Duration.SECONDS));


    Interval r1 = Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(3, Duration.SECONDS));
    Interval r2 = Interval.betweenClosedOpen(Duration.of(5, Duration.SECONDS), Duration.of(6, Duration.SECONDS));
    Interval r3 = Interval.betweenClosedOpen(Duration.of(7, Duration.SECONDS), Duration.of(10, Duration.SECONDS));
    Interval r5 = Interval.betweenClosedOpen(Duration.of(11, Duration.SECONDS), Duration.of(15, Duration.SECONDS));
    Interval r7 = Interval.betweenClosedOpen(Duration.of(18, Duration.SECONDS), Duration.of(22, Duration.SECONDS));

    Windows tw = new Windows(false).set(List.of(r1,r2,r3,r5,r7), true);

    Windows res = fsm.filter(null, null, tw);

    var expected = new Windows(false).set(List.of(r2,r3), true);
    assertEquals(expected, res);
  }

  @Test
  public void testMinGapAfter() {
    FilterSequenceMinGapAfter fsm = new FilterSequenceMinGapAfter(Duration.of(2, Duration.SECONDS));

    Interval r1 = Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(3, Duration.SECONDS));
    Interval r2 = Interval.betweenClosedOpen(Duration.of(5, Duration.SECONDS), Duration.of(6, Duration.SECONDS));
    Interval r3 = Interval.betweenClosedOpen(Duration.of(7, Duration.SECONDS), Duration.of(10, Duration.SECONDS));
    Interval r5 = Interval.betweenClosedOpen(Duration.of(11, Duration.SECONDS), Duration.of(15, Duration.SECONDS));
    Interval r7 = Interval.betweenClosedOpen(Duration.of(18, Duration.SECONDS), Duration.of(22, Duration.SECONDS));

    List<Interval> ranges = new ArrayList<>();
    ranges.add(r1);
    ranges.add(r2);
    ranges.add(r3);
    ranges.add(r5);
    ranges.add(r7);

    Windows tw = new Windows(false).set(ranges, true);

    Windows expected = new Windows(false).set(List.of(r1,r5,r7), true);
    Windows res = fsm.filter(null, null, tw);
    assertEquals(res, expected);

  }


}
