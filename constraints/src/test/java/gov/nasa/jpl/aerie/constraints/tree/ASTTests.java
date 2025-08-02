package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EDSLConstraintResult;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.model.Violation;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalMap;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.UnsplittableSpanException;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static gov.nasa.jpl.aerie.constraints.Assertions.assertEquivalent;
import static gov.nasa.jpl.aerie.constraints.time.Interval.FOREVER;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.at;
import static gov.nasa.jpl.aerie.constraints.time.Interval.interval;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class ASTTests {

  @Test
  public void testAssignGaps() {
    final var simResults = new SimulationResults(
        Instant.EPOCH,
        Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var originalWindows = new Windows()
        .set(Interval.between(-2, -1, SECONDS), true)
        .set(Interval.between(1, 2, SECONDS), false);

    final var defaultWindows = new Windows(false)
        .set(Interval.between(Duration.ZERO, Duration.MAX_VALUE), true);

    final var result = new AssignGaps<>(
        Supplier.of(originalWindows),
        Supplier.of(defaultWindows)
    ).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows(false)
        .set(Interval.between(-2, -1, SECONDS), true)
        .set(Interval.between(0, Inclusive, 1, Exclusive, SECONDS), true)
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testWindowsValue() {
    final var simResults = new SimulationResults(
        Instant.EPOCH,
        Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result = new WindowsValue(true, Optional.empty()).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows(Interval.between(0, 20, SECONDS), true);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testNot() {
    final var simResults = new SimulationResults(
        Instant.EPOCH,
        Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var windows = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), false)
        .set(Interval.at(20, SECONDS), true);

    final var result = new Not(Supplier.of(windows)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), false)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), false);

    assertEquivalent(expected, result);
  }

  @Test
  public void testWindowsStarts() {
    final var simResults = new SimulationResults(
        Instant.EPOCH,
        Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var windows = new Windows()
        .set(Interval.between(Duration.MIN_VALUE, Duration.MIN_VALUE.plus(Duration.HOUR)), true)
        .set(Interval.between(-10, 10, SECONDS), false)
        .set(Interval.between(0, 1, SECONDS), true)
        .set(Interval.between(2, Exclusive, 3, Inclusive, SECONDS), true)
        .set(Interval.between(100, 101, SECONDS), true);

    final var result = new Starts<>(Supplier.of(windows)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.MIN_VALUE.plus(Duration.HOUR), Inclusive), false)
        .set(Interval.between(-10, 10, SECONDS), false)
        .set(Interval.at(0, SECONDS), true)
        .set(Interval.at(2, SECONDS), true)
        .set(Interval.between(100, Exclusive, 101, Inclusive, SECONDS), false);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testWindowsEnds() {
    final var simResults = new SimulationResults(
        Instant.EPOCH,
        Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var windows = new Windows()
        .set(Interval.between(Duration.MAX_VALUE.minus(Duration.HOUR), Duration.MAX_VALUE), true)
        .set(Interval.between(-10, 10, SECONDS), false)
        .set(Interval.between(0, 1, SECONDS), true)
        .set(Interval.between(2, Inclusive, 3, Exclusive, SECONDS), true)
        .set(Interval.between(100, 101, SECONDS), true);

    final var result = new Ends<>(Supplier.of(windows)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(Duration.MAX_VALUE.minus(Duration.HOUR), Inclusive, Duration.MAX_VALUE, Inclusive), false)
        .set(Interval.between(-10, 10, SECONDS), false)
        .set(Interval.at(1, SECONDS), true)
        .set(Interval.at(3, SECONDS), true)
        .set(Interval.between(100, Inclusive, 101, Exclusive, SECONDS), false);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testSpansStarts() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans();
    spans.add(Interval.between(0, Inclusive, 5, Exclusive, SECONDS));
    spans.add(Interval.between(0, Exclusive, 5, Exclusive, SECONDS));
    spans.add(Interval.at(20, SECONDS));

    final var result = new Starts<>(Supplier.of(spans)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Spans();
    expected.add(Interval.at(0, SECONDS));
    expected.add(Interval.at(0, SECONDS));
    expected.add(Interval.at(20, SECONDS));

    assertEquivalent(expected, result);
  }

  @Test
  public void testSpansEnds() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans();
    spans.add(Interval.between(0, Inclusive, 5, Exclusive, SECONDS));
    spans.add(Interval.between(0, Exclusive, 5, Inclusive, SECONDS));
    spans.add(Interval.at(20, SECONDS));

    final var result = new Ends<>(Supplier.of(spans)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Spans();
    expected.add(Interval.at(5, SECONDS));
    expected.add(Interval.at(5, SECONDS));
    expected.add(Interval.at(20, SECONDS));

    assertEquivalent(expected, result);
  }

  @Test
  public void testSplitWindows() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var windows = new Windows(false)
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(10000000, Exclusive, 15000001, Exclusive, MICROSECONDS), true);

    final var result = new WindowsFromSpans(new Split<>(Supplier.of(windows), 3, Exclusive, Exclusive)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows(false)
    		.set(Interval.between(0, Inclusive, 1666666, Exclusive, MICROSECONDS), true)
    		.set(Interval.between(1666666, Exclusive, 3333332, Exclusive, MICROSECONDS), true)
    		.set(Interval.between(3333332, Exclusive, 5000000, Exclusive, MICROSECONDS), true)

    		.set(Interval.between(10000000, Exclusive, 11666667, Exclusive, MICROSECONDS), true)
    		.set(Interval.between(11666667, Exclusive, 13333334, Exclusive, MICROSECONDS), true)
    		.set(Interval.between(13333334, Exclusive, 15000001, Exclusive, MICROSECONDS), true);

    assertEquivalent(expected, result);
  }

  @Test
  public void testSplitSpans() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans();
    spans.add(Interval.between(0, Inclusive, 5, Exclusive, SECONDS));
    spans.add(Interval.between(0, Exclusive, 5000001, Inclusive, MICROSECONDS));

    final var result = new Split<>(Supplier.of(spans), 3, Inclusive, Exclusive).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Spans();
    expected.add(Interval.between(0, Inclusive, 1666666, Exclusive, MICROSECONDS));
    expected.add(Interval.between(1666666, Inclusive, 3333332, Exclusive, MICROSECONDS));
    expected.add(Interval.between(3333332, Inclusive, 5000000, Exclusive, MICROSECONDS));

    expected.add(Interval.between(0, Exclusive, 1666667, Exclusive, MICROSECONDS));
    expected.add(Interval.between(1666667, Inclusive, 3333334, Exclusive, MICROSECONDS));
    expected.add(Interval.between(3333334, Inclusive, 5000001, Inclusive, MICROSECONDS));

    assertIterableEquals(expected, result);
  }

  @Test
  public void testUnsplittableInterval() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans(
        Interval.at(5, SECONDS)
    );

    assertThrows(UnsplittableSpanException.class, () -> new Split<Spans>(Supplier.of(spans), 3, Inclusive, Exclusive).evaluate(simResults, new EvaluationEnvironment()));
  }

  @Test
  public void testAnd() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var left = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true);

    final var right = new Windows()
        .set(Interval.between(0, Inclusive, 5, Inclusive, SECONDS), true)
        .set(Interval.between(7, Inclusive, 8, Exclusive, SECONDS), true)
        .set(Interval.between(10, Inclusive, 12, Inclusive, SECONDS), true)
        .set(Interval.between(15, Inclusive, 20, Exclusive, SECONDS), true);

    final var result = new And(Supplier.of(left), Supplier.of(right)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between( 0, Inclusive,  5, Exclusive, SECONDS), true)
        .set(Interval.at(7, SECONDS), true)
        .set(Interval.between( 10, Exclusive,  12, Inclusive, SECONDS), true);

    assertEquivalent(expected, result);
  }

  @Test
  public void testOr() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 26, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var left = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true)
        .set(Interval.between(25, Inclusive, 26, Exclusive, SECONDS), false);

    final var right = new Windows()
        .set(Interval.between(0, Inclusive, 5, Inclusive, SECONDS), true)
        .set(Interval.between(7, Inclusive, 8, Exclusive, SECONDS), true)
        .set(Interval.between(10, Inclusive, 12, Inclusive, SECONDS), true)
        .set(Interval.between(15, Inclusive, 20, Exclusive, SECONDS), true)
        .set(Interval.between(25, Inclusive, 26, Exclusive, SECONDS), false);

    final var result = new Or(Supplier.of(left), Supplier.of(right)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(  0, Inclusive,   5, Inclusive, SECONDS), true)
        .set(Interval.between(  6, Inclusive,   8, Exclusive, SECONDS), true)
        .set(Interval.between(  8, Exclusive,   9, Exclusive, SECONDS), true)
        .set(Interval.between( 10, Inclusive,  20, Inclusive, SECONDS), true)
        .set(Interval.between(25, Inclusive, 26, Exclusive, SECONDS), false);

    assertEquivalent(expected, result);
  }

  @Test
  public void testExpandBy() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(-100, 200, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var left = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true)
        .set(interval(22, 23, SECONDS), false);

    final var expandByFromStart = Duration.of(-1, SECONDS);
    final var expandByFromEnd = Duration.of(0, SECONDS);

    final var result = new ShiftEdges<>(Supplier.of(left), Supplier.of(expandByFromStart), Supplier.of(expandByFromEnd)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(-1, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(7, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(9, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.between(19, 20, SECONDS), true)
        .set(at(22, SECONDS), false);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testShrink() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(-100, 200, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var left = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true)
        .set(interval(22, 23, SECONDS), false);

    final var clampFromStart = Duration.of(1, SECONDS);
    final var clampFromEnd = Duration.negate(Duration.of(1, SECONDS));

    final var result = new ShiftEdges<>(Supplier.of(left), Supplier.of(clampFromStart), Supplier.of(clampFromEnd)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(1, Inclusive, 4, Exclusive, SECONDS), true)
        .set(Interval.between(7, Inclusive, 6, Inclusive, SECONDS), true)
        .set(Interval.between(9, Exclusive, 8, Exclusive, SECONDS), true)
        .set(Interval.between(11, Exclusive, 14, Exclusive, SECONDS), true)
        .set(interval(21, 24, SECONDS), false);

    assertIterableEquals(expected, result);
  }

  @Test
  public void testRealValue() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result = new RealValue(7, 3.14, Optional.empty()).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new LinearProfile(
        Segment.of(simResults.bounds, new LinearEquation(Duration.ZERO, 7, 3.14))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testDiscreteValue() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result = new DiscreteValue(SerializedValue.of("IDLE"), Optional.empty()).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new DiscreteProfile(
        Segment.of(simResults.bounds, SerializedValue.of("IDLE"))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testRealParameter() {
    final var act = new ActivityInstance(
        1,
        "typeA",
        Map.of("p1", SerializedValue.of(2)),
        Interval.between(0, 10, SECONDS));

    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(act),
        Map.of(),
        Map.of()
    );
    final var environment = new EvaluationEnvironment(Map.of("act", act), Map.of(), Map.of(), Map.of(), Map.of());

    final var result = new RealParameter("act", "p1").evaluate(simResults, environment);

    final var expected = new LinearProfile(
        Segment.of(FOREVER, new LinearEquation(Duration.ZERO, 2, 0))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testDiscreteResource() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(
            "real1", new LinearProfile(Segment.of(Interval.at(1, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 0, 1))),
            "real2", new LinearProfile(Segment.of(Interval.at(2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 0, 1))),
            "real3", new LinearProfile(Segment.of(Interval.at(3, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 0, 1)))
        ),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.at(4, SECONDS), SerializedValue.of("one"))),
            "discrete2", new DiscreteProfile(Segment.of(Interval.at(5, SECONDS), SerializedValue.of("two"))),
            "discrete3", new DiscreteProfile(Segment.of(Interval.at(6, SECONDS), SerializedValue.of("three")))
        )
    );

    final var result = new DiscreteResource("discrete2").evaluate(simResults, new EvaluationEnvironment());

    final var expected = simResults.discreteProfiles.get("discrete2");

    assertEquivalent(expected, result);
  }


  @Test
  public void testDiscreteShiftBy() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of(
            "discrete", new DiscreteProfile(Segment.of(Interval.between(1, 2, SECONDS), SerializedValue.of("much value")))
        )
    );

    final var result = new ShiftBy<>(new DiscreteResource("discrete"), new DurationLiteral(Duration.of(1, SECONDS))).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new DiscreteProfile(Segment.of(Interval.between(2, 3, SECONDS), SerializedValue.of("much value")));

    assertEquivalent(expected, result);
  }

  @Test
  public void testValueAt(){
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.between(1,2, SECONDS), SerializedValue.of("one")),
                                             Segment.of(Interval.between(2,4, SECONDS), SerializedValue.of("two")),
                                             Segment.of(Interval.between(4,7, SECONDS), SerializedValue.of("three"))
                                             )
        )
    );

    final var result = new ValueAt<>(
        new ProfileExpression<>(new DiscreteResource("discrete1")),
        new SpansFromWindows(new WindowsWrapperExpression(new Windows(false).set(Interval.at(Duration.of(3, SECONDS)), true))))
        .evaluate(simResults, new EvaluationEnvironment());

    final var expected = new DiscreteProfile(IntervalMap.<SerializedValue>builder()
                                                        .set(Segment.of(Interval.between(0, 20, SECONDS), SerializedValue.of("two")))
                                                        .build());

    assertIterableEquals(expected, result);
  }

  @Test
  public void testRealResource() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(
            "real1", new LinearProfile(Segment.of(Interval.at(1, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 0, 1))),
            "real2", new LinearProfile(Segment.of(Interval.at(2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 0, 1))),
            "real3", new LinearProfile(Segment.of(Interval.at(3, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 0, 1)))
        ),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.at(4, SECONDS), SerializedValue.of("one"))),
            "discrete2", new DiscreteProfile(Segment.of(Interval.at(5, SECONDS), SerializedValue.of(2))),
            "discrete3", new DiscreteProfile(Segment.of(Interval.at(6, SECONDS), SerializedValue.of("three")))
        )
    );

    final var result = new RealResource("real2").evaluate(simResults, new EvaluationEnvironment());

    final var expected = simResults.realProfiles.get("real2");

    assertEquivalent(expected, result);
  }

  @Test
  public void testRealResourceOnDiscrete() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(
            "real1", new LinearProfile(Segment.of(Interval.at(1, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 0, 1))),
            "real2", new LinearProfile(Segment.of(Interval.at(2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 0, 1))),
            "real3", new LinearProfile(Segment.of(Interval.at(3, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 0, 1)))
        ),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.at(4, SECONDS), SerializedValue.of("one"))),
            "discrete2", new DiscreteProfile(Segment.of(Interval.at(5, SECONDS), SerializedValue.of(2))),
            "discrete3", new DiscreteProfile(Segment.of(Interval.at(6, SECONDS), SerializedValue.of("three")))
        )
    );

    final var result = new RealResource("discrete2").evaluate(simResults, new EvaluationEnvironment());

    final var expected = new LinearProfile(Segment.of(Interval.at(5, SECONDS), new LinearEquation(Duration.of(5, SECONDS), 2, 0)));

    assertEquivalent(expected, result);
  }

  @Test
  public void testRealResourceFailureOnDiscrete() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(
            "real1", new LinearProfile(Segment.of(Interval.at(1, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 0, 1))),
            "real2", new LinearProfile(Segment.of(Interval.at(2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 0, 1))),
            "real3", new LinearProfile(Segment.of(Interval.at(3, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 0, 1)))
        ),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.at(4, SECONDS), SerializedValue.of("one"))),
            "discrete2", new DiscreteProfile(Segment.of(Interval.at(5, SECONDS), SerializedValue.of(2))),
            "discrete3", new DiscreteProfile(Segment.of(Interval.at(6, SECONDS), SerializedValue.of("three")))
        )
    );

    try {
      new RealResource("discrete1").evaluate(simResults, new EvaluationEnvironment());
    } catch (final InputMismatchException e) {
      return;
    }
    fail("Expected RealResource node to fail conversion of discrete resource to real resource");
  }

  @Test
  public void testRealResourceOnNonexistentResource() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    try {
      new RealResource("does_not_exist").evaluate(simResults, new EvaluationEnvironment());
    } catch (final InputMismatchException e) {
      return;
    }
    fail("Expected RealResource node to fail on non-existent resource");
  }

  @Test
  public void testRealShiftBy() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(
            "real", new LinearProfile(Segment.of(Interval.between(1, 2, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 1, 1)))
        ),
        Map.of()
    );

    final var result = new ShiftBy<>(new RealResource("real"), new DurationLiteral(Duration.of(1, SECONDS))).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new LinearProfile(Segment.of(Interval.between(2, 3, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 1, 1)));

    assertEquivalent(expected, result);
  }

  @Test
  public void testForEachActivity() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(
            new ActivityInstance(1, "TypeA", Map.of(), Interval.between(4, 6, SECONDS)),
            new ActivityInstance(2, "TypeB", Map.of(), Interval.between(5, 7, SECONDS)),
            new ActivityInstance(3, "TypeA", Map.of(), Interval.between(9, 10, SECONDS))
        ),
        Map.of(),
        Map.of()
    );

    final var spansSupplied = new Windows(Segment.of(Interval.FOREVER, false),
                                              Segment.of(interval(4, 6, SECONDS), true)).intoSpans(simResults.bounds);
    final var result = new ForEachActivitySpans(
        "TypeA",
        "act",
        new Supplier<>(spansSupplied)
    ).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Spans();
    expected.add(interval(4,6, SECONDS));
    expected.add(interval(4,6, SECONDS));

    assertIterableEquals(expected, result);
  }

  @Test
  public void testNestedForEachActivityViolations() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(
            new ActivityInstance(1, "TypeA", Map.of(), Interval.between(4, 6, SECONDS)),
            new ActivityInstance(2, "TypeB", Map.of(), Interval.between(5, 7, SECONDS)),
            new ActivityInstance(3, "TypeA", Map.of(), Interval.between(9, 10, SECONDS))
        ),
        Map.of(),
        Map.of()
    );

    final var result = new ForEachActivityViolations(
        "TypeA",
        "act",
        new ForEachActivityViolations(
            "TypeB",
            "act",
            new UniqueSupplier<>(
                () -> new EDSLConstraintResult(
                    List.of(new Violation(List.of(Interval.between(1, 2, SECONDS)), new ArrayList<>())),
                    new ArrayList<>()
                )
            )
        )
    ).evaluate(simResults, new EvaluationEnvironment());

    // We expect two violations because there are two activities of TypeA
    // The details of the violation will be the same, since we are using a supplier
    final var expected = new EDSLConstraintResult(
        List.of(
            new Violation(List.of(Interval.between(1, 2, SECONDS)), List.of(1L, 2L)),
            new Violation(List.of(Interval.between(1, 2, SECONDS)), List.of(3L, 2L))
        ),
        List.of()
    );

    assertEquals(expected, result);
  }

  @Test
  public void testNestedForEachActivitySpans() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(
            new ActivityInstance(1, "TypeA", Map.of(), Interval.between(4, 6, SECONDS)),
            new ActivityInstance(2, "TypeB", Map.of(), Interval.between(5, 7, SECONDS)),
            new  ActivityInstance(3, "TypeA", Map.of(), Interval.between(9, 10, SECONDS))
        ),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans(interval(4, 6, SECONDS));
    final var result = new ForEachActivitySpans(
        "TypeA",
        "act",
        new ForEachActivitySpans(
            "TypeB",
            "act",
            new Supplier<>(spans)
        )
    ).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Spans(interval(4, 6, SECONDS), interval(4, 6, SECONDS));

    assertEquivalent(expected, result);
  }

  @Test
  public void testViolationsOf() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var windows = new Windows()
        .set(Interval.between(1, 4, SECONDS), true)
        .set(Interval.between(5, 6, SECONDS), false)
        .set(Interval.between(7,20, SECONDS), true);

    final var result = new ViolationsOfWindows(new Supplier<>(windows)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new EDSLConstraintResult(
        List.of(new Violation(List.of(Interval.between(5, 6, SECONDS)), List.of())),
        List.of(
            Interval.between(Duration.ZERO, Inclusive, Duration.SECOND, Exclusive),
            Interval.between(4, Exclusive, 5, Exclusive, SECONDS),
            Interval.between(6, Exclusive, 7, Exclusive, SECONDS)
        )
    );

    assertEquals(expected, result);
  }

  @Test
  public void testDuring() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var activityInstance = new ActivityInstance(
        1,
        "TypeA",
        Map.of(),
        Interval.between(4, 8, SECONDS));

    final var environment = new EvaluationEnvironment(
        Map.of(
            "act",
            activityInstance
        ),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of()
    );

    final var result = new ActivitySpan("act").evaluate(simResults, environment);

    final var expected = new Spans(
        List.of(Segment.of(interval(4, 8, SECONDS), Optional.of(new Spans.Metadata(activityInstance))))
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testStartOf() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var environment = new EvaluationEnvironment(
        Map.of(
            "act",
            new ActivityInstance(
                1,
                "TypeA",
                Map.of(),
                Interval.between(4, 8, SECONDS))
        ),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of()
    );

    final var result = new StartOf("act").evaluate(simResults, environment);

    final var expected = new Windows(
        Segment.of(simResults.bounds, false),
        Segment.of(at(4, SECONDS), true)
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testEndOf() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var environment = new EvaluationEnvironment(
        Map.of(
            "act",
            new ActivityInstance(
                1,
                "TypeA",
                Map.of(),
                Interval.between(4, 8, SECONDS))
        ),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of()
    );

    final var result = new EndOf("act").evaluate(simResults, environment);

    final var expected = new Windows(
        Segment.of(simResults.bounds, false),
        Segment.of(at(8, SECONDS), true)
    );

    assertEquivalent(expected, result);
  }

  @Test
  public void testLongerThan() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var left = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true)
        .set(interval(22, 23, SECONDS), false)
        .set(interval(24, 30, SECONDS), false);

    final var right = Duration.of(2, SECONDS);
    final var result = new LongerThan(Supplier.of(left), Supplier.of(right)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), false)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), false)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), false)
        .set(interval(22, 23, SECONDS), false)
        .set(interval(24, 30, SECONDS), false);

    assertEquivalent(expected, result);
  }

  @Test
  public void testShorterThan() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var left = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true)
        .set(interval(22, 23, SECONDS), false)
        .set(interval(24, 30, SECONDS), false);

    final var right = Duration.of(2, SECONDS);
    final var result = new ShorterThan(Supplier.of(left), Supplier.of(right)).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), false)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), true)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), false)
        .set(Interval.at(20, SECONDS), true)
        .set(interval(22, 23, SECONDS), false)
        .set(interval(24, 30, SECONDS), false);

    assertEquivalent(expected, result);
  }

  @Test
  public void testExternalDiscreteResource() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var environment = new EvaluationEnvironment(
        Map.of(
            "real1", new LinearProfile(Segment.of(Interval.at(1, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 0, 1))),
            "real2", new LinearProfile(Segment.of(Interval.at(2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 0, 1))),
            "real3", new LinearProfile(Segment.of(Interval.at(3, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 0, 1)))
        ),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.at(4, SECONDS), SerializedValue.of("one"))),
            "discrete2", new DiscreteProfile(Segment.of(Interval.at(5, SECONDS), SerializedValue.of("two"))),
            "discrete3", new DiscreteProfile(Segment.of(Interval.at(6, SECONDS), SerializedValue.of("three")))
        )
    );

    final var result = new DiscreteResource("discrete2").evaluate(simResults, environment);

    final var expected = environment.discreteExternalProfiles().get("discrete2");

    assertEquivalent(expected, result);
  }

  @Test
  public void testExternalRealResource() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var environment = new EvaluationEnvironment(
        Map.of(
            "real1", new LinearProfile(Segment.of(Interval.at(1, SECONDS), new LinearEquation(Duration.of(1, SECONDS), 0, 1))),
            "real2", new LinearProfile(Segment.of(Interval.at(2, SECONDS), new LinearEquation(Duration.of(2, SECONDS), 0, 1))),
            "real3", new LinearProfile(Segment.of(Interval.at(3, SECONDS), new LinearEquation(Duration.of(3, SECONDS), 0, 1)))
        ),
        Map.of(
            "discrete1", new DiscreteProfile(Segment.of(Interval.at(4, SECONDS), SerializedValue.of("one"))),
            "discrete2", new DiscreteProfile(Segment.of(Interval.at(5, SECONDS), SerializedValue.of("two"))),
            "discrete3", new DiscreteProfile(Segment.of(Interval.at(6, SECONDS), SerializedValue.of("three")))
        )
    );

    final var result = new RealResource("real2").evaluate(simResults, environment);

    final var expected = environment.realExternalProfiles().get("real2");

    assertEquivalent(expected, result);
  }

  @Test
  public void testSpansAlias() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans(
        Interval.between(0, 1, SECONDS)
    );

    final var environment = new EvaluationEnvironment(
        Map.of(),
        Map.of("my spans", spans),
        Map.of(),
        Map.of(),
        Map.of()
    );

    final var result = new SpansAlias("my spans").evaluate(simResults, environment);

    assertEquivalent(spans, result);
  }

  @Test
  public void testSpansFromInterval() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var interval = new AbsoluteInterval(Optional.of(Instant.EPOCH), Optional.of(Instant.ofEpochSecond(10)), Optional.empty(), Optional.empty());
    final var environment = new EvaluationEnvironment();

    final var result = new SpansInterval(interval).evaluate(simResults, environment);

    final var expected = new Spans(Interval.between(0, 10, SECONDS));

    assertEquals(expected, result);
  }

  @Test
  public void testHorizonInterval() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, Inclusive, 20, Exclusive, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var interval = new AbsoluteInterval(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    final var result = interval.evaluate(simResults);

    final var expected = Interval.between(0, Inclusive, 20, Exclusive, SECONDS);

    assertEquals(expected, result);
  }

  @Test
  public void testShiftByBoundsAdjustment() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var expression = new ShiftBy<>(
        new DiscreteValue(SerializedValue.of("strang")),
        Supplier.of(Duration.of(10, SECONDS))
    );

    final var result = expression.evaluate(simResults);

    final var expected = new DiscreteProfile(
        Segment.of(Interval.between(0, 20, SECONDS), SerializedValue.of("strang"))
    );

    assertIterableEquals(expected, result);
  }

  @Test
  public void testShiftWindowsEdgesBoundsAdjustment() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var crossingStartOfPlan = new Windows(false).set(Interval.between(-1, 1, SECONDS), true);

    final var result1 = new ShiftEdges<>(
        Supplier.of(crossingStartOfPlan),
        Supplier.of(Duration.ZERO),
        Supplier.of(Duration.of(10, SECONDS))
    ).evaluate(simResults);
    final var expected1 = new Windows(false).set(Interval.between(-1, 11, SECONDS), true).select(simResults.bounds);
    assertIterableEquals(expected1, result1);

    final var result2 = new ShiftEdges<>(
        Supplier.of(crossingStartOfPlan),
        Supplier.of(Duration.of(-10, SECONDS)),
        Supplier.of(Duration.ZERO)
    ).evaluate(simResults);
    final var expected2 = new Windows(false).set(Interval.between(0, 1, SECONDS), true).select(simResults.bounds);
    assertIterableEquals(expected2, result2);

    final var crossingEndOfPlan = new Windows(false).set(Interval.between(19, 21, SECONDS), true);

    final var result3 = new ShiftEdges<>(
        Supplier.of(crossingEndOfPlan),
        Supplier.of(Duration.ZERO),
        Supplier.of(Duration.of(10, SECONDS))
    ).evaluate(simResults);
    final var expected3 = new Windows(false).set(Interval.between(19, 20, SECONDS), true).select(simResults.bounds);
    assertIterableEquals(expected3, result3);

    final var result4 = new ShiftEdges<>(
        Supplier.of(crossingEndOfPlan),
        Supplier.of(Duration.of(-10, SECONDS)),
        Supplier.of(Duration.ZERO)
    ).evaluate(simResults);
    final var expected4 = new Windows(false).set(Interval.between(9, 21, SECONDS), true).select(simResults.bounds);
    assertIterableEquals(expected2, result2);
  }

  @Test
  void testSpansConnectTo() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, Inclusive, 20, Exclusive, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result = new SpansConnectTo(
        Supplier.of(new Spans(
            Interval.between(0, 1, SECONDS),
            Interval.between(5, 7, SECONDS),
            Interval.between(10, Inclusive, 11, Exclusive, SECONDS),
            Interval.between(13, 14, SECONDS)
        )),
        Supplier.of(new Spans(
            Interval.between(2, 3, SECONDS),
            Interval.between(4, 6, SECONDS),
            Interval.between(8, 9, SECONDS),
            Interval.between(11, 12, SECONDS)
        ))
    ).evaluate(simResults);

    final var expected = new Spans(
        Interval.between(1, 2, SECONDS),
        Interval.between(7, 8, SECONDS),
        Interval.at(11, SECONDS),
        Interval.between(14, Inclusive, 20, Exclusive, SECONDS)
    );

    assertIterableEquals(expected, result);
  }

  @Test
  void testSpansConnectToMetadata() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, Inclusive, 20, Exclusive, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result = new SpansConnectTo(
        Supplier.of(new Spans(
            Segment.of(Interval.between(0, 1, SECONDS), Optional.of(new Spans.Metadata(new ActivityInstance(2, "2", Map.of(), FOREVER)))),
            Segment.of(Interval.between(5, 7, SECONDS), Optional.empty())
        )),
        Supplier.of(new Spans(
            Interval.between(2, 3, SECONDS),
            Interval.between(8, 9, SECONDS)
        ))
    ).evaluate(simResults);

    final var expected = new Spans(
        Segment.of(Interval.between(1, 2, SECONDS), Optional.of(new Spans.Metadata(new ActivityInstance(2, "2", Map.of(), FOREVER)))),
        Segment.of(Interval.between(7, 8, SECONDS), Optional.empty())
    );

    assertIterableEquals(expected, result);
  }

  @Test
  public void testRollingThresholdExcess() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans(
        // These two are out of order to make sure RollingThreshold's hull operation correctly handles unsorted spans.
        Interval.between(4, 5, SECONDS),
        Interval.between(0, 1, SECONDS),
        Interval.between(2, 3, SECONDS),

        Interval.between(14, 15, SECONDS),
        Interval.between(16, 17, SECONDS),
        Interval.between(18, 19, SECONDS)
    );

    final var result1 = new RollingThreshold(
        Supplier.of(spans),
        Supplier.of(Duration.of(10, SECONDS)),
        Supplier.of(Duration.of(2500, MILLISECOND)),
        RollingThreshold.RollingThresholdAlgorithm.ExcessHull
    ).evaluate(simResults);

    final var expected1 = new EDSLConstraintResult(
        List.of(
            new Violation(List.of(Interval.between(0, 5, SECONDS)), List.of()),
            new Violation(List.of(Interval.between(14, 19, SECONDS)), List.of())
        ),
        List.of()
    );

    assertEquals(expected1, result1);

    final var result2 = new RollingThreshold(
        Supplier.of(spans),
        Supplier.of(Duration.of(10, SECONDS)),
        Supplier.of(Duration.of(2500, MILLISECOND)),
        RollingThreshold.RollingThresholdAlgorithm.ExcessSpans
    ).evaluate(simResults);

    final var expected2 = new EDSLConstraintResult(
        List.of(
            new Violation(
                List.of(
                    Interval.between(4, 5, SECONDS),
                    Interval.between(0, 1, SECONDS),
                    Interval.between(2, 3, SECONDS)
                ), List.of()
            ),
            new Violation(
                List.of(
                    Interval.between(14, 15, SECONDS),
                    Interval.between(16, 17, SECONDS),
                    Interval.between(18, 19, SECONDS)
                ), List.of()
            )
        ), List.of()
    );

    assertEquals(expected2, result2);
  }

  @Test
  public void tesRollingThresholdDeficit() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var spans = new Spans(
        Interval.between(0, 1, SECONDS),
        Interval.between(2, 3, SECONDS),
        Interval.between(4, 5, SECONDS),

        Interval.between(14, 15, SECONDS),
        Interval.between(16, 17, SECONDS),
        Interval.between(18, 19, SECONDS)
    );

    final var result1 = new RollingThreshold(
        Supplier.of(spans),
        Supplier.of(Duration.of(10, SECONDS)),
        Supplier.of(Duration.of(2500, MILLISECOND)),
        RollingThreshold.RollingThresholdAlgorithm.DeficitHull
    ).evaluate(simResults);

    final var expected1 = new EDSLConstraintResult(
        List.of(
            new Violation(List.of(Interval.between(1, Exclusive, 18, Exclusive, SECONDS)), List.of())
        ),
        List.of()
    );

    assertEquals(expected1, result1);

    final var result2 = new RollingThreshold(
        Supplier.of(spans),
        Supplier.of(Duration.of(10, SECONDS)),
        Supplier.of(Duration.of(2500, MILLISECOND)),
        RollingThreshold.RollingThresholdAlgorithm.DeficitSpans
    ).evaluate(simResults);

    final var expected2 = new EDSLConstraintResult(
        List.of(
            new Violation(List.of(
                Interval.between(1, Exclusive, 2, Exclusive, SECONDS),
                Interval.between(3, Exclusive, 4, Exclusive, SECONDS),
                Interval.between(5, Exclusive, 14, Exclusive, SECONDS),
                Interval.between(15, Exclusive, 16, Exclusive, SECONDS),
                Interval.between(17, Exclusive, 18, Exclusive, SECONDS)
            ), List.of())
        ),
        List.of()
    );

    assertEquals(expected2, result2);
  }

  @Test
  void testSpansShiftEdges() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result1 = new ShiftEdges<>(
        Supplier.of(new Spans(
            Interval.between(0, 1, SECONDS),
            Interval.between(0, 2, SECONDS),
            Interval.between(0, Inclusive, 2, Exclusive, SECONDS),
            Interval.between(0, 3, SECONDS)
        )),
        Supplier.of(Duration.of(1, SECONDS)),
        Supplier.of(Duration.of(-1, SECONDS))
    ).evaluate(simResults);

    final var expected1 = new Spans(
        Interval.at(1, SECONDS),
        Interval.between(1, 2, SECONDS)
    );

    assertIterableEquals(expected1, result1);

    final var result2 = new ShiftEdges<>(
        Supplier.of(new Spans(
            Interval.between(0, 1, SECONDS),
            Interval.between(0, 2, SECONDS),
            Interval.between(0, Inclusive, 2, Exclusive, SECONDS),
            Interval.between(0, 3, SECONDS)
        )),
        Supplier.of(Duration.of(3, SECONDS)),
        Supplier.of(Duration.of(4, SECONDS))
    ).evaluate(simResults);

    final var expected2 = new Spans(
        Interval.between(3, 5, SECONDS),
        Interval.between(3, 6, SECONDS),
        Interval.between(3, Inclusive, 6, Exclusive, SECONDS),
        Interval.between(3, 7, SECONDS)
    );

    assertIterableEquals(expected2, result2);
  }

  @Test
  void testSpansSelectWhenTrue() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var result = new SpansSelectWhenTrue(
        Supplier.of(new Spans(
            Interval.between(0, 1, SECONDS), // fully inside
            Interval.between(3, 4, SECONDS), // fully outside
            Interval.between(5, 7, SECONDS), // half outside
            Interval.between(10, 14, SECONDS) // split across multiple
        )),
        Supplier.of(new Windows(false).set(
            List.of(
                Interval.between(0, 1, SECONDS),
                Interval.between(6, 8, SECONDS),
                Interval.between(9, 11, SECONDS),
                Interval.between(13, 15, SECONDS)
            ),
            true
        ))
    ).evaluate(simResults);

    final var expected = new Spans(
        Interval.between(0, 1, SECONDS),
        Interval.between(6, 7, SECONDS),
        Interval.between(10, 11, SECONDS),
        Interval.between(13, 14, SECONDS)
    );

    assertIterableEquals(expected, result);
  }
  @Test
  public void testKeepTrueSegment(){
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var windows = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), true)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), false)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), true)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), true)
        .set(interval(22, 23, SECONDS), false)
        .set(interval(24, 30, SECONDS), false);

    final var result = new KeepTrueSegment(Supplier.of(windows), 2).evaluate(simResults, new EvaluationEnvironment());
    final var result2 = new KeepTrueSegment(Supplier.of(windows), -2).evaluate(simResults, new EvaluationEnvironment());
    final var result3 = new KeepTrueSegment(Supplier.of(windows), 5).evaluate(simResults, new EvaluationEnvironment());

    final var expected = new Windows()
        .set(Interval.between(0, Inclusive, 5, Exclusive, SECONDS), false)
        .set(Interval.between(6, Inclusive, 7, Inclusive, SECONDS), false)
        .set(Interval.between(8, Exclusive, 9, Exclusive, SECONDS), false)
        .set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), true)
        .set(Interval.at(20, SECONDS), false)
        .set(interval(22, 23, SECONDS), false)
        .set(interval(24, 30, SECONDS), false);

    assertEquivalent(expected, result);
    assertEquivalent(expected, result2);
    assertEquivalent(expected.set(Interval.between(10, Exclusive, 15, Exclusive, SECONDS), false), result3);
  }

  @Test
  public void testSpansContains() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var parents = new Spans(
      interval(0, 3, SECONDS), // has no children inside it
      interval(6, 9, SECONDS), // has one child of duration 3
      interval(12, 15, SECONDS), // has two children, total duration 2
      interval(16, 19, SECONDS) // has one partially-contained child, intersection duration 3
    );

    final var children = new Spans(
        interval(6, 9, SECONDS),
        interval(12, 13, SECONDS),
        interval(14, 15, SECONDS),
        interval(16, 20, SECONDS)
    );

    // require min count 1
    final var count1Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.of(1),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )))
            .evaluate(simResults);
    final var count1Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(0, 3, SECONDS), false)
        .set(interval(16, 19, SECONDS), false);
    assertIterableEquals(count1Expected, count1Result);

    // require min count 2
    final var count2Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.of(2),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )))
            .evaluate(simResults);
    final var count2Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(0, 3, SECONDS), false)
        .set(interval(6, 9, SECONDS), false)
        .set(interval(16, 19, SECONDS), false);
    assertIterableEquals(count2Expected, count2Result);

    // require max count 1
    final var count3Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.empty(),
            Optional.of(1),
            Optional.empty(),
            Optional.empty()
        )))
            .evaluate(simResults);
    final var count3Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(12, 15, SECONDS), false);
    assertIterableEquals(count3Expected, count3Result);

    // require min duration 3s
    final var duration1Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.empty(),
            Optional.empty(),
            Optional.of(Supplier.of(Duration.of(3, SECONDS))),
            Optional.empty()
        )))
            .evaluate(simResults);
    final var duration1Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(0, 3, SECONDS), false)
        .set(interval(12, 15, SECONDS), false);
    assertIterableEquals(duration1Expected, duration1Result);

    // require max duration 2.5s
    final var duration2Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(Supplier.of(Duration.of(2500, MILLISECOND)))
        )))
            .evaluate(simResults);
    final var duration2Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(6, 9, SECONDS), false)
        .set(interval(16, 19, SECONDS), false);
    assertIterableEquals(duration2Expected, duration2Result);

    // require min count 1 and max duration 2s
    final var combinedResult =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.of(1),
            Optional.empty(),
            Optional.empty(),
            Optional.of(Supplier.of(Duration.of(2, SECONDS)))
        )))
            .evaluate(simResults);
    final var combinedExpected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(0, 3, SECONDS), false)
        .set(interval(6, 9, SECONDS), false)
        .set(interval(16, 19, SECONDS), false);
    assertIterableEquals(combinedExpected, combinedResult);
  }

  @Test
  void testSpansContainsNotEnoughChildren() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var parents = new Spans(
        interval(0, 3, SECONDS),
        interval(6, 9, SECONDS),
        interval(12, 15, SECONDS),
        interval(16, 19, SECONDS)
    );

    final var children = new Spans(
        interval(1, 2, SECONDS)
    );

    // require min count 1
    final var count1Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.of(1),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )))
            .evaluate(simResults);
    final var count1Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(6, 9, SECONDS), false)
        .set(interval(12, 15, SECONDS), false)
        .set(interval(16, 19, SECONDS), false);
    assertIterableEquals(count1Expected, count1Result);
  }

  @Test
  void testSpansContainsLookahead() {
    final var simResults = new SimulationResults(
        Instant.EPOCH, Interval.between(0, 20, SECONDS),
        List.of(),
        Map.of(),
        Map.of()
    );

    final var parents = new Spans(
        interval(0, 3, SECONDS),
        interval(5, 9, SECONDS),
        interval(5, 9, SECONDS),
        interval(11, 13, SECONDS),
        interval(11, 13, SECONDS)
    );

    final var children = new Spans(
        interval(3, 4, SECONDS),
        interval(6, 7, SECONDS),
        interval(7, 8, SECONDS)
    );

    // require min count 1
    final var count1Result =
        (new SpansContains(Supplier.of(parents), Supplier.of(children), new SpansContains.Requirement(
            Optional.of(2),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )))
            .evaluate(simResults);
    final var count1Expected = new Windows(interval(0, 20, SECONDS), true)
        .set(interval(0, 3, SECONDS), false)
        .set(interval(11, 13, SECONDS), false);
    assertIterableEquals(count1Expected, count1Result);
  }

  /**
   * An expression that yields the same aliased object every time it is evaluated.
   */
  private static final class Supplier<T> implements Expression<T> {
    private final T value;

    public Supplier(final T value) {
      this.value = value;
    }


    @Override
    public T evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
      return this.value;
    }

    @Override
    public void extractResources(final Set<String> names) { }

    @Override
    public String prettyPrint(final String prefix) {
      return value.toString();
    }

    public static <V> Supplier<V> of(V value) {
      return new Supplier<>(value);
    }
  }

  /**
   * An expression that calls a supplier function and produces a new object every time it is evaluated.
   */
  private record UniqueSupplier<T>(java.util.function.Supplier<T> supplier) implements Expression<T> {
    @Override
    public T evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
      return supplier.get();
    }

    @Override
    public String prettyPrint(final String prefix) {
      return this.toString();
    }

    @Override
    public void extractResources(final Set<String> names) {

    }
  }
}
