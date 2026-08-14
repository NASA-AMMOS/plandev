package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.TimeRangeExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.transformers.Transformers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestEnvelopes {
  @Test
  public void testEnvelopes() {

    var horizon = new Windows(false).set(Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(20, Duration.SECONDS)), true);

    Interval r1 = Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(10, Duration.SECONDS));
    Interval r2 = Interval.betweenClosedOpen(Duration.of(12, Duration.SECONDS), Duration.of(20, Duration.SECONDS));

    var resetExpr = new TimeRangeExpression.Builder().from(new Windows(false).set(List.of(r1, r2), true)).build();

    Interval r3 = Interval.betweenClosedOpen(Duration.of(6, Duration.SECONDS), Duration.of(11, Duration.SECONDS));
    Interval r4 = Interval.betweenClosedOpen(Duration.of(3, Duration.SECONDS), Duration.of(7, Duration.SECONDS));
    Interval r5 = Interval.betweenClosedOpen(Duration.of(0, Duration.SECONDS), Duration.of(3, Duration.SECONDS));
    Interval r6 = Interval.betweenClosedOpen(Duration.of(3, Duration.SECONDS), Duration.of(4, Duration.SECONDS));

    var firstType = new TimeRangeExpression.Builder().from(new Windows(false).set(List.of(r4, r6), true)).build();

    var secondType = new TimeRangeExpression.Builder().from(new Windows(false).set(List.of(r3, r5), true)).build();


    var envelope = new Transformers.EnvelopeBuilder()
        .withinEach(resetExpr)
        .when(firstType)
        .when(secondType)
        .build();

    TimeRangeExpression tre = new TimeRangeExpression.Builder()
        .from(resetExpr)
        .thenTransform(envelope)
        .name("encounter_enveloper_TRE")
        .build();

    var ranges = tre.computeRange(null, null, horizon);
    assertTrue(ranges.includes(Interval.betweenClosedOpen(Duration.of(1, Duration.SECONDS), Duration.of(10, Duration.SECONDS))));
    ranges = ranges.removeTrueSegment(0);
    assertTrue(ranges.stream().noneMatch(Segment::value));
  }
}


