package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.at;
import static gov.nasa.jpl.aerie.constraints.time.Interval.interval;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

public class WindowsTest {

  @Test
  public void constructorTests() {

    //just verify the constructors work right
    var windows = new Windows(
        Segment.of(interval(Duration.MIN_VALUE, Duration.of(0, SECONDS)), true),
        Segment.of(at(1, SECONDS), true),
        Segment.of(interval(2, 3, SECONDS), false),
        Segment.of(at(3, SECONDS), true)
    );

    assertEquals(windows.minTrueTimePoint().get().getKey(), Duration.MIN_VALUE);
  }

  @Test
  public void setAndSetAll() {

    Windows w = new Windows()
        .set(interval(Duration.ZERO, Duration.MAX_VALUE), false);

    //added correctly?
    assertEquals(w.size(), 1);

    //add at front, back, in between, make sure they're added correctly
    w = w.set(at(Duration.ZERO), true) //don't coalesce
         .set(at(Duration.MAX_VALUE), false) //coalesce implictly, nothing happens w/ this line
         .set(interval(3, 50, SECONDS), true);

    //added correctly?
    assertEquals(w.size(), 4);

    //do a setAll
    Windows nw = new Windows()
        .set(w);

    assertEquals(w, nw);

  }

  @Test
  public void isEmptyVsIsFalse() {

    //create a interval of just true blocks
    Windows soTrue = new Windows(Segment.of(interval(3, 5, SECONDS), true),
                                 Segment.of(interval(7, 10, SECONDS), true),
                                 Segment.of(interval(12, 15, SECONDS), true),
                                 Segment.of(interval(30, 35, SECONDS), true));

    //check isFalse, verify the return value is false as either true or null
    //isEmpty is different from isFalse. isEmpty checks is there anything at all - all false would still return false
    assertTrue(StreamSupport.stream(soTrue.spliterator(), false).allMatch(Segment::value));
    assertFalse(soTrue.isEmpty());
  }

  @Test
  public void unsetMain() {

    Windows result = new Windows(interval(Duration.ZERO, Duration.MAX_VALUE), true)

        //try all unset variations, with different bound types
        .unset(interval(5, Exclusive, 7, Inclusive, SECONDS))
        .unset(List.of(interval(9, Exclusive, 12, Inclusive, SECONDS)))
        .unset(List.of(interval(13, Exclusive, 15, Inclusive, SECONDS)))
        .unset(
            List.of(
                interval(20, Exclusive, 25, Exclusive, SECONDS),
                interval(26, Exclusive, 27, Exclusive, SECONDS)
            )
        )
        .unset(interval(29, 30, SECONDS))
        .unset(interval(29, 30, SECONDS));

    //values of result should never have changed!
    assertTrue(StreamSupport.stream(result.spliterator(), false).allMatch(Segment::value));

    //check values
    Windows expected = new Windows(Segment.of(interval(0, 5, SECONDS), true),
                                   Segment.of(interval(7, Exclusive, 9, Inclusive, SECONDS), true),
                                   Segment.of(interval(12, Exclusive, 13, Inclusive, SECONDS), true),
                                   Segment.of(interval(15, Exclusive, 20, Inclusive, SECONDS), true),
                                   Segment.of(interval(25, Inclusive, 26, Inclusive, SECONDS), true),
                                   Segment.of(interval(27, Inclusive, 29, Exclusive, SECONDS), true),
                                   Segment.of(interval(Duration.of(30, SECONDS), Exclusive,
                                                       Duration.MAX_VALUE, Inclusive), true));

    assertIterableEquals(expected, result);
  }

  @Test
  public void and() {

    //just test truth table associated with the map2 call for this method, don't waste time
    //  with bounds checking as that was done for map2!!

    // orig | inFilter | output
    //  T   |    T     |   T
    //  T   |    F     |   F
    //  T   |    N     |   N
    //  F   |    T     |   F
    //  F   |    F     |   F
    //  F   |    N     |   N
    //  N   |    T     |   N
    //  N   |    F     |   N
    //  N   |    N     |   N

    Windows orig = new Windows(
        Segment.of(interval(1, 4, SECONDS), true),
        Segment.of(interval(6, 11, SECONDS), false)
    );

    Windows intersectMe = new Windows(
        Segment.of(interval(0, 2, SECONDS), true),
        Segment.of(interval(2, Exclusive, 3, Inclusive, SECONDS), false),
        Segment.of(interval(7, 8, SECONDS), true),
        Segment.of(interval(9, 10, SECONDS), false),
        Segment.of(interval(12, 13, SECONDS), true),
        Segment.of(interval(14, 15, SECONDS), false)
    );
    Windows intersection = orig.and(intersectMe);

    Windows expected = new Windows(
        Segment.of(interval(1, 2, SECONDS), true),
        Segment.of(interval(2, Exclusive, 3, Inclusive, SECONDS), false),
        Segment.of(interval(6, 11, SECONDS), false),
        Segment.of(interval(14, 15, SECONDS), false)
    );
    assertIterableEquals(expected, intersection);
  }

  @Test
  public void minMaxTimePoints() {

    //check empty
    Windows w = new Windows();
    assertFalse(w.minTrueTimePoint().isPresent());
    assertFalse(w.maxTrueTimePoint().isPresent());

    //check only 1 interval
    w = w.set(interval(Duration.ZERO, Duration.MAX_VALUE), false);
    assertFalse(w.minTrueTimePoint().isPresent());
    assertFalse(w.maxTrueTimePoint().isPresent());

    //multiple intervals
    w = w.set(interval(3, 50, SECONDS), true)
         .set(interval(75, 200, SECONDS), true);
    assertEquals(w.minTrueTimePoint().get(), Pair.of(Duration.of(3, SECONDS), Inclusive));
    assertEquals(w.maxTrueTimePoint().get(), Pair.of(Duration.of(200, SECONDS), Inclusive));

    //verify points work too
    w = w.unset(at(Duration.MAX_VALUE))
         .set(at(Duration.ZERO), true);
    assertEquals(w.minTrueTimePoint().get(), Pair.of(Duration.of(0, SECONDS), Inclusive));
    assertEquals(w.maxTrueTimePoint().get(), Pair.of(Duration.of(200, SECONDS), Inclusive));
  }

  @Test
  public void complement() {
    Windows main = new Windows(
        Segment.of(at(0, SECONDS), false),
        Segment.of(interval(1, 3, SECONDS), true),
        Segment.of(interval(4, 7, SECONDS), false),
        Segment.of(interval(8, 10, SECONDS), true),
        Segment.of(at(12, SECONDS), true),
        Segment.of(at(13, SECONDS), false),
        Segment.of(at(Duration.MAX_VALUE), true)
    ).unset(at(9, SECONDS));

    main = main.not();

    //check values
    Windows expected = new Windows(
        Segment.of(at(0, SECONDS), true),
        Segment.of(interval(1, 3, SECONDS), false),
        Segment.of(interval(4, 7, SECONDS), true),
        Segment.of(interval(8, Inclusive, 9, Exclusive, SECONDS), false),
        Segment.of(interval(9, Exclusive, 10, Inclusive, SECONDS), false),
        Segment.of(at(12, SECONDS), false),
        Segment.of(at(13, SECONDS), true),
        Segment.of(at(Duration.MAX_VALUE), false)
    );
    assertIterableEquals(expected, main);
  }

  @Test
  public void filterByDurationNormal1() {

    //just test truth table associated with the map2 call for this method, don't waste time
    //  with bounds checking as that was done for map2!!

    // orig | inFilter | output
    //  T   |    T     |   T
    //  T   |    F     |   F
    //  T   |    N     |   N
    //  F   |    T     |   F
    //  F   |    F     |   F
    //  F   |    N     |   N
    //  N   |    T     |   N
    //  N   |    F     |   N
    //  N   |    N     |   N

    Windows orig = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                               Segment.of(interval(6, 9, SECONDS), false),
                               Segment.of(interval(11, 14, SECONDS), true),
                               Segment.of(interval(16, 17, SECONDS), true));


    orig = orig.filterByDuration(Duration.of(3, SECONDS), Duration.of(3, SECONDS));

    Windows expected = new Windows(Segment.of(interval(0, 4, SECONDS), false),
                                   Segment.of(interval(6, 9, SECONDS), false),
                                   Segment.of(interval(11, 14, SECONDS), true),
                                   Segment.of(interval(16, 17, SECONDS), false));
    assertIterableEquals(expected, orig);
  }

  @Test
  public void filterByDurationNormal2() {


    Windows orig = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                               Segment.of(interval(6, 9, SECONDS), false),
                               Segment.of(interval(11, 14, SECONDS), true),
                               Segment.of(interval(16, 17, SECONDS), true));


    orig = orig.filterByDuration(Duration.of(1, SECONDS), Duration.of(3, SECONDS));

    Windows expected = new Windows(Segment.of(interval(0, 4, SECONDS), false),
                                   Segment.of(interval(6, 9, SECONDS), false),
                                   Segment.of(interval(11, 14, SECONDS), true),
                                   Segment.of(interval(16, 17, SECONDS), true));
    assertIterableEquals(expected, orig);
  }

  @Test
  public void filterByDurationZero() {

    Windows orig = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                               Segment.of(interval(6, 9, SECONDS), false),
                               Segment.of(interval(11, 14, SECONDS), true),
                               Segment.of(interval(16, 17, SECONDS), true));


    orig = orig.filterByDuration(Duration.ZERO, Duration.ZERO);

    Windows expected = new Windows(Segment.of(interval(0, 4, SECONDS), false),
                                   Segment.of(interval(6, 9, SECONDS), false),
                                   Segment.of(interval(11, 14, SECONDS), false),
                                   Segment.of(interval(16, 17, SECONDS), false));
    assertIterableEquals(expected, orig);
  }

  @Test
  public void filterByDurationMinZeroMaxMax() {

    Windows orig = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                               Segment.of(interval(6, 9, SECONDS), false),
                               Segment.of(interval(11, 14, SECONDS), true),
                               Segment.of(interval(16, 17, SECONDS), true));


    orig = orig.filterByDuration(Duration.ZERO, Duration.MAX_VALUE);

    Windows expected = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                                   Segment.of(interval(6, 9, SECONDS), false),
                                   Segment.of(interval(11, 14, SECONDS), true),
                                   Segment.of(interval(16, 17, SECONDS), true));
    assertIterableEquals(expected, orig);
  }

  @Test
  public void filterByDurationMinMaxMaxMax() {

    final var orig = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                                 Segment.of(interval(6, 9, SECONDS), false),
                                 Segment.of(interval(11, 14, SECONDS), true),
                                 Segment.of(interval(16, 17, SECONDS), true));


    final var result = orig.filterByDuration(Duration.MAX_VALUE, Duration.MAX_VALUE);

    Windows expected = new Windows(Segment.of(interval(0, 4, SECONDS), false),
                                   Segment.of(interval(6, 9, SECONDS), false),
                                   Segment.of(interval(11, 14, SECONDS), false),
                                   Segment.of(interval(16, 17, SECONDS), false));

    assertIterableEquals(expected, result);
  }

  @Test
  public void filterByDurationMinMaxMaxZero() {

    final Windows orig = new Windows(Segment.of(interval(0, 4, SECONDS), true),
                                     Segment.of(interval(6, 9, SECONDS), false),
                                     Segment.of(interval(11, 14, SECONDS), true),
                                     Segment.of(interval(16, 17, SECONDS), true));

    assertThrows(
        Exception.class,
        () -> orig.filterByDuration(Duration.MAX_VALUE, Duration.ZERO),
        "MaxDur +2562047788:00:54.775807 must be greater than MinDur +00:00:00.000000"
    );
  }

  @Test
  public void removeEndsEmpty() {
    Windows empty = new Windows();
    Windows rf = empty.removeTrueSegment(0);
    Windows rl = empty.removeTrueSegment(-1);
    Windows rfl = empty.removeTrueSegment(0).removeTrueSegment(-1);
    assertEquals(empty, rf);
    assertEquals(rf, rl);
    assertEquals(rl, rfl);
  }

  @Test
  public void shiftByStretch() {
    Windows orig = new Windows(Segment.of(at(0, SECONDS), true),
                               Segment.of(interval(1, 2, SECONDS), false),
                               Segment.of(interval(5, 6, SECONDS), true),
                               Segment.of(interval(7, 8, SECONDS), false),
                               Segment.of(interval(8, Exclusive, 10, Exclusive, SECONDS), true),
                               Segment.of(interval(13, 14, SECONDS), true),
                               Segment.of(interval(14, Exclusive, 17, Exclusive, SECONDS), false),
                               Segment.of(at(Duration.MAX_VALUE), true)); //long overflow if at max value

    Windows result = orig.shiftEdges(Duration.of(-1, SECONDS), Duration.of(1, SECONDS));


    Windows expected = new Windows(
        Segment.of(interval(-1, 1, SECONDS), true),
        Segment.of(interval(4, Inclusive, 11, Exclusive, SECONDS), true),
        Segment.of(interval(12, 15, SECONDS), true),
        Segment.of(interval(15, Exclusive, 16, Exclusive, SECONDS), false),
        Segment.of(interval(Duration.MAX_VALUE.minus(Duration.of(1, SECONDS)), Duration.MAX_VALUE), true)
    );
    assertIterableEquals(expected, result);
  }

  @Test
  public void accumulatedDuration() {
    final var acc = new Windows(false)
        .set(interval(0, 1, SECONDS), true)
        .set(interval(2, Inclusive, 3, Exclusive, SECONDS), true)
        .set(interval(4, Exclusive, 5, Inclusive, SECONDS), true)
        .set(at(6, SECONDS), true)
        .unset(interval(8, 9, SECONDS))
        .accumulatedDuration(MILLISECOND);

    final var expected = new LinearProfile(
        Segment.of(interval(Duration.MIN_VALUE, Inclusive, Duration.ZERO, Exclusive), new LinearEquation(Duration.ZERO, 0, 0)),
        Segment.of(interval(0, Inclusive, 1, Inclusive, SECOND), new LinearEquation(Duration.ZERO, 0, 1000)),
        Segment.of(interval(1, Exclusive, 2, Exclusive, SECOND), new LinearEquation(SECOND, 1000, 0)),
        Segment.of(interval(2, Inclusive, 3, Exclusive, SECOND), new LinearEquation(Duration.of(2, SECOND), 1000, 1000)),
        Segment.of(interval(3, 4, SECOND), new LinearEquation(Duration.ZERO, 2000, 0)),
        Segment.of(interval(4, Exclusive, 5, Inclusive, SECOND), new LinearEquation(Duration.of(4, SECOND), 2000, 1000)),
        Segment.of(interval(5, Exclusive, 8, Exclusive, SECOND), new LinearEquation(Duration.ZERO, 3000, 0)),
        Segment.of(interval(Duration.of(9, SECOND), Exclusive, Duration.MAX_VALUE, Inclusive), new LinearEquation(Duration.ZERO, 3000, 0))
    );

    assertIterableEquals(expected, acc);
  }

  @Test
  public void shiftByConnectedIntervals() {

    var orig = new Windows()
        .set(interval(0, 10, SECONDS), false)
        .set(interval(0, 2, SECONDS), true)
        .set(interval(8, 10, SECONDS), true);

    var fromStartPosFromEndPos = orig.shiftEdges(Duration.of(-1, SECONDS), Duration.of(1, SECONDS));
    assertIterableEquals(
        new Windows(interval(0, 10, SECONDS), false)
            .set(interval(-1, 3, SECONDS), true)
            .set(interval(7, 11, SECONDS), true),
        fromStartPosFromEndPos
    );

    var fromStartPosFromEndNeg = orig.shiftEdges(Duration.of(1, SECONDS), Duration.of(-1, SECONDS));
    assertEquals(
        new Windows(interval(1, Exclusive, 9, Exclusive, SECONDS), false)
            .set(at(1, SECONDS), true)
            .set(at(9, SECONDS), true),
        fromStartPosFromEndNeg
    );

    var fromStartNegFromEndPos = orig.shiftEdges(Duration.of(1, SECONDS), Duration.of(1, SECONDS));
    assertEquals(
        new Windows(interval(1, 11, SECONDS), false)
            .set(interval(1, 3, SECONDS), true)
            .set(interval(9, 11, SECONDS), true),
        fromStartNegFromEndPos
    );

    var fromStartNegFromEndNeg = orig.shiftEdges(Duration.of(-1, SECONDS), Duration.of(-1, SECONDS));
    assertEquals(
        new Windows(interval(-1, 9, SECONDS), false)
            .set(interval(-1, 1, SECONDS), true)
            .set(interval(7, 9, SECONDS), true),
        fromStartNegFromEndNeg
    );

    var removal = orig.shiftEdges(Duration.of(0, SECONDS), Duration.of(-3, SECONDS));
    assertEquals(
        new Windows(interval(-1, Exclusive, 8, Exclusive, SECONDS), false),
        removal
    );
  }

  @Test
  public void shiftByDisconnectedPoints() {
    var orig = new Windows()
        .set(at(0, SECONDS), true)
        .set(at(2, SECONDS), false);

    var fromStartPosFromEndPos = orig.shiftEdges(Duration.of(-1, SECONDS), Duration.of(1, SECONDS));
    assertIterableEquals(
        new Windows(interval(-1, 1, SECONDS), true),
        fromStartPosFromEndPos
    );

    var fromStartPosFromEndNeg = orig.shiftEdges(Duration.of(1, SECONDS), Duration.of(-1, SECONDS));
    assertIterableEquals(
        new Windows(interval(1, 3, SECONDS), false),
        fromStartPosFromEndNeg
    );

    var fromStartNegFromEndPos = orig.shiftEdges(Duration.of(1, SECONDS), Duration.of(1, SECONDS));
    assertIterableEquals(
        new Windows()
            .set(at(1, SECONDS), true)
            .set(at(3, SECONDS), false),
        fromStartNegFromEndPos
    );

    var fromStartNegFromEndNeg = orig.shiftEdges(Duration.of(-1, SECONDS), Duration.of(-1, SECONDS));
    assertIterableEquals(
        new Windows()
            .set(at(-1, SECONDS), true)
            .set(at(1, SECONDS), false),
        fromStartNegFromEndNeg
    );
  }

  @Test
  public void includes() {

    var w = new Windows(
        Segment.of(interval(0, 2, SECONDS), true),
        Segment.of(interval(4, 5, SECONDS), false),
        Segment.of(interval(5, 6, SECONDS), true)
    );

    assertTrue(w.includes(interval(1, 2, SECONDS)));
    assertTrue(w.includes(new Windows(
        Segment.of(interval(0, 1, SECONDS), true),
        Segment.of(interval(5, 6, SECONDS), true)
    )));
    assertTrue(w.includes(new Windows(
        Segment.of(interval(0, 1, SECONDS), true),
        Segment.of(interval(5, 6, SECONDS), true),
        Segment.of(interval(3, 5, SECONDS), false)
    )));
    assertTrue(w.includes(at(1, SECONDS)));
    assertFalse(w.includes(interval(0, 3, SECONDS)));
  }

  @Test
  public void includesEmpty() {
    final var x = new Windows();

    assertTrue(x.includes(Interval.EMPTY));
    assertFalse(x.includes(at(0, MICROSECONDS)));
  }


  @Test
  public void iterator() {
    var nw = new Windows()
        .set(interval(1, 2, SECONDS), true);

    //some simple iterator tests
    var iter = nw.iterator();
    iter.forEachRemaining($ -> assertEquals($, Segment.of(interval(1, 2, SECONDS), true)));

    iter = nw.iterator(); //everything has been consumed - need to reset
    assertEquals(iter.next(), Segment.of(interval(1, 2, SECONDS), true));
    assertFalse(iter.hasNext());
  }

  @Test
  public void intoSpans() {
    final var windowsWithGaps = new Windows(
        Segment.of(interval(0, 2, SECONDS), true),
        Segment.of(interval(1, 3, SECONDS), true),
        Segment.of(interval(5, 5, SECONDS), true),
        Segment.of(interval(6, 8, SECONDS), false)
    );

    assertThrows(
        InvalidGapsException.class,
        () -> windowsWithGaps.intoSpans(Interval.between(0, 10, SECONDS)),
        "cannot convert Windows with gaps into Spans (unbounded gap to -infinity)"
    );

    final var windowsWithoutGaps = new Windows(false).set(windowsWithGaps);

    var expected = new Spans(
        interval(0, 3, SECONDS),
        interval(5, 5, SECONDS)
    );

    assertIterableEquals(expected, windowsWithoutGaps.intoSpans(Interval.FOREVER));

    expected = new Spans(
        interval(1, 2, SECONDS)
    );
    assertIterableEquals(
        expected,
        windowsWithGaps.intoSpans(interval(1, 2, SECONDS))
    );
  }
}
