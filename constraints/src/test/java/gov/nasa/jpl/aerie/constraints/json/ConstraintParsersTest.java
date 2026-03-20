package gov.nasa.jpl.aerie.constraints.json;

import gov.nasa.jpl.aerie.constraints.tree.AbsoluteInterval;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.tree.AccumulatedDuration;
import gov.nasa.jpl.aerie.constraints.tree.ActivitySpan;
import gov.nasa.jpl.aerie.constraints.tree.ActivityWindow;
import gov.nasa.jpl.aerie.constraints.tree.And;
import gov.nasa.jpl.aerie.constraints.tree.AssignGaps;
import gov.nasa.jpl.aerie.constraints.tree.Changes;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteParameter;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteResource;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteValue;
import gov.nasa.jpl.aerie.constraints.tree.DurationLiteral;
import gov.nasa.jpl.aerie.constraints.tree.EndOf;
import gov.nasa.jpl.aerie.constraints.tree.Ends;
import gov.nasa.jpl.aerie.constraints.tree.Equal;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivityViolations;
import gov.nasa.jpl.aerie.constraints.tree.GreaterThan;
import gov.nasa.jpl.aerie.constraints.tree.GreaterThanOrEqual;
import gov.nasa.jpl.aerie.constraints.tree.LessThan;
import gov.nasa.jpl.aerie.constraints.tree.LessThanOrEqual;
import gov.nasa.jpl.aerie.constraints.tree.Not;
import gov.nasa.jpl.aerie.constraints.tree.NotEqual;
import gov.nasa.jpl.aerie.constraints.tree.Or;
import gov.nasa.jpl.aerie.constraints.tree.Plus;
import gov.nasa.jpl.aerie.constraints.tree.ProfileExpression;
import gov.nasa.jpl.aerie.constraints.tree.Rate;
import gov.nasa.jpl.aerie.constraints.tree.RealParameter;
import gov.nasa.jpl.aerie.constraints.tree.RealResource;
import gov.nasa.jpl.aerie.constraints.tree.RealValue;
import gov.nasa.jpl.aerie.constraints.tree.SpansFromWindows;
import gov.nasa.jpl.aerie.constraints.tree.Split;
import gov.nasa.jpl.aerie.constraints.tree.StartOf;
import gov.nasa.jpl.aerie.constraints.tree.Starts;
import gov.nasa.jpl.aerie.constraints.tree.Times;
import gov.nasa.jpl.aerie.constraints.tree.Transition;
import gov.nasa.jpl.aerie.constraints.tree.ViolationsOfWindows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsFromSpans;
import gov.nasa.jpl.aerie.constraints.tree.WindowsValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Optional;

import static gov.nasa.jpl.aerie.constraints.Assertions.assertEquivalent;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.absoluteIntervalP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.constraintP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.discreteProfileExprP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.discreteResourceP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.linearProfileExprP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.spansExpressionP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.windowsExpressionP;
import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.windowsValueP;
import static org.junit.jupiter.api.Assertions.assertEquals;


public final class ConstraintParsersTest {
  @Test
  public void testParseAbsoluteInterval() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "AbsoluteInterval");
    json.put("start", "2020-03-30T19:00:00Z");
    json.put("end", "2020-03-30T20:00:00Z");
    json.put("startInclusivity", "Inclusive");
    json.put("endInclusivity", "Exclusive");
    final var result = absoluteIntervalP.parse(json).getSuccessOrThrow();

    final var expected = new AbsoluteInterval(
        Optional.of(Instant.parse("2020-03-30T19:00:00Z")),
        Optional.of(Instant.parse("2020-03-30T20:00:00Z")),
        Optional.of(Interval.Inclusivity.Inclusive),
        Optional.of(Interval.Inclusivity.Exclusive)
    );

    assertEquals(expected, result);

    final var emptyJson = JsonNodeFactory.instance.objectNode();
    emptyJson.put("kind", "AbsoluteInterval");
    final var emptyResult = absoluteIntervalP.parse(emptyJson).getSuccessOrThrow();
    final var emptyExpected = new AbsoluteInterval(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );

    assertEquals(emptyExpected, emptyResult);
  }
  @Test
  public void testParseDiscreteValue() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "DiscreteProfileValue");
    json.put("value", false);
    final var result = discreteProfileExprP.parse(json).getSuccessOrThrow();

    final var expected =
        new DiscreteValue(SerializedValue.of(false), Optional.empty());

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseChanges() {
    final var innerObj = JsonNodeFactory.instance.objectNode();
    innerObj.put("kind", "DiscreteProfileValue");
    innerObj.put("value", false);

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "ProfileChanges");
    json.set("expression", innerObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Changes<>(
            new ProfileExpression<>(
                new DiscreteValue(SerializedValue.of(false), Optional.empty())));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseDiscreteResource() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "DiscreteProfileResource");
    json.put("name", "ResA");
    final var result = discreteResourceP.parse(json).getSuccessOrThrow();

    final var expected =
        new DiscreteResource("ResA");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseTransition() {
    final var profileObj = JsonNodeFactory.instance.objectNode();
    profileObj.put("kind", "DiscreteProfileResource");
    profileObj.put("name", "ResA");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "DiscreteProfileTransition");
    json.set("profile", profileObj);
    json.put("from", "old");
    json.put("to", "new");
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Transition(
            new DiscreteResource("ResA"),
            SerializedValue.of("old"),
            SerializedValue.of("new"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseRealParameter() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileParameter");
    json.put("alias", "act");
    json.put("name", "pJones");
    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected = new RealParameter("act", "pJones");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseRealValue() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileValue");
    json.put("value", 3.4);
    json.put("rate", 2.2);
    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected = new RealValue(3.4, 2.2, Optional.empty());

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseRealResource() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileResource");
    json.put("name", "ResA");
    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected = new RealResource("ResA");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParsePlus() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfilePlus");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected =
        new Plus(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseTimes() {
    final var profileObj = JsonNodeFactory.instance.objectNode();
    profileObj.put("kind", "RealProfileResource");
    profileObj.put("name", "ResA");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileTimes");
    json.set("profile", profileObj);
    json.put("multiplier", 2.7);
    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected =
        new Times(
            new RealResource("ResA"),
            2.7);

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseRate() {
    final var profileObj = JsonNodeFactory.instance.objectNode();
    profileObj.put("kind", "RealProfileResource");
    profileObj.put("name", "ResA");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileRate");
    json.set("profile", profileObj);
    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected =
        new Rate(
            new RealResource("ResA"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseDuringWindow() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionActivityWindow");
    json.put("alias", "TEST");
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected = new ActivityWindow("TEST");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseDuringSpan() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "SpansExpressionActivitySpan");
    json.put("alias", "TEST");
    final var result = spansExpressionP.parse(json).getSuccessOrThrow();

    final var expected = new ActivitySpan("TEST");

    assertEquivalent(expected, result);
  }


  @Test
  public void testParseStartOf() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionStartOf");
    json.put("alias", "TEST");
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected = new StartOf("TEST");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseEndOf() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionEndOf");
    json.put("alias", "TEST");
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected = new EndOf("TEST");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseParameter() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "DiscreteProfileParameter");
    json.put("alias", "TEST");
    json.put("name", "paramesan");
    final var result = discreteProfileExprP.parse(json).getSuccessOrThrow();

    final var expected = new DiscreteParameter("TEST", "paramesan");

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseWindowsValue() {
    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionValue");
    json.put("value", true);
    final var result = windowsValueP.parse(json).getSuccessOrThrow();

    final var expected = new WindowsValue(true, Optional.empty());

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseEqual() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "ExpressionEqual");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Equal<>(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseNotEqual() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "ExpressionNotEqual");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new NotEqual<>(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseLessThan() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileLessThan");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new LessThan(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseLessThanOrEqual() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileLessThanOrEqual");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new LessThanOrEqual(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseGreaterThanOrEqual() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileGreaterThanOrEqual");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new GreaterThanOrEqual(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseGreaterThan() {
    final var leftObj = JsonNodeFactory.instance.objectNode();
    leftObj.put("kind", "RealProfileResource");
    leftObj.put("name", "ResA");

    final var rightObj = JsonNodeFactory.instance.objectNode();
    rightObj.put("kind", "RealProfileResource");
    rightObj.put("name", "ResB");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileGreaterThan");
    json.set("left", leftObj);
    json.set("right", rightObj);
    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new GreaterThan(
            new RealResource("ResA"),
            new RealResource("ResB"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseAnd() {
    final var spanA = JsonNodeFactory.instance.objectNode();
    spanA.put("kind", "SpansExpressionActivitySpan");
    spanA.put("alias", "A");

    final var fromSpansA = JsonNodeFactory.instance.objectNode();
    fromSpansA.put("kind", "WindowsExpressionFromSpans");
    fromSpansA.set("spansExpression", spanA);

    final var spanB = JsonNodeFactory.instance.objectNode();
    spanB.put("kind", "SpansExpressionActivitySpan");
    spanB.put("alias", "B");

    final var fromSpansB = JsonNodeFactory.instance.objectNode();
    fromSpansB.put("kind", "WindowsExpressionFromSpans");
    fromSpansB.set("spansExpression", spanB);

    final var expressionsArr = JsonNodeFactory.instance.arrayNode();
    expressionsArr.add(fromSpansA);
    expressionsArr.add(fromSpansB);

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionAnd");
    json.set("expressions", expressionsArr);

    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new And(
            new WindowsFromSpans(new ActivitySpan("A")),
            new WindowsFromSpans(new ActivitySpan("B")));

    assertEquivalent(expected, result);
  }


  @Test
  public void testParseOr() {
    final var windowA = JsonNodeFactory.instance.objectNode();
    windowA.put("kind", "WindowsExpressionActivityWindow");
    windowA.put("alias", "A");

    final var windowB = JsonNodeFactory.instance.objectNode();
    windowB.put("kind", "WindowsExpressionActivityWindow");
    windowB.put("alias", "B");

    final var expressionsArr = JsonNodeFactory.instance.arrayNode();
    expressionsArr.add(windowA);
    expressionsArr.add(windowB);

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionOr");
    json.set("expressions", expressionsArr);

    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Or(
            new ActivityWindow("A"),
            new ActivityWindow("B"));
    assertEquivalent(expected, result);
  }

  @Test
  public void testParseNot() {
    final var innerObj = JsonNodeFactory.instance.objectNode();
    innerObj.put("kind", "WindowsExpressionActivityWindow");
    innerObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionNot");
    json.set("expression", innerObj);

    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Not(
            new ActivityWindow("A"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseStarts() {
    final var innerObj = JsonNodeFactory.instance.objectNode();
    innerObj.put("kind", "WindowsExpressionActivityWindow");
    innerObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "IntervalsExpressionStarts");
    json.set("expression", innerObj);

    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Starts<>(
            new ActivityWindow("A"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseEnds() {
    final var innerObj = JsonNodeFactory.instance.objectNode();
    innerObj.put("kind", "WindowsExpressionActivityWindow");
    innerObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "IntervalsExpressionEnds");
    json.set("expression", innerObj);

    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Ends<>(
            new ActivityWindow("A"));

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseSplitWindows() {
    final var intervalsObj = JsonNodeFactory.instance.objectNode();
    intervalsObj.put("kind", "WindowsExpressionActivityWindow");
    intervalsObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "SpansExpressionSplit");
    json.set("intervals", intervalsObj);
    json.put("numberOfSubIntervals", 3);
    json.put("internalStartInclusivity", "Exclusive");
    json.put("internalEndInclusivity", "Exclusive");

    final var result = spansExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Split<>(
            new ActivityWindow("A"),
            3,
            Interval.Inclusivity.Exclusive,
            Interval.Inclusivity.Exclusive
        );

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseSplitSpans() {
    final var intervalsObj = JsonNodeFactory.instance.objectNode();
    intervalsObj.put("kind", "SpansExpressionActivitySpan");
    intervalsObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "SpansExpressionSplit");
    json.set("intervals", intervalsObj);
    json.put("numberOfSubIntervals", 3);
    json.put("internalStartInclusivity", "Inclusive");
    json.put("internalEndInclusivity", "Exclusive");

    final var result = spansExpressionP.parse(json).getSuccessOrThrow();

    final var expected =
        new Split<>(
            new ActivitySpan("A"),
            3,
            Interval.Inclusivity.Inclusive,
            Interval.Inclusivity.Exclusive
        );

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseAccumulatedDurationWindows() {
    final var intervalsObj = JsonNodeFactory.instance.objectNode();
    intervalsObj.put("kind", "WindowsExpressionActivityWindow");
    intervalsObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileAccumulatedDuration");
    json.set("intervalsExpression", intervalsObj);
    json.put("unit", 101);

    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected =
        new AccumulatedDuration<>(
            new ActivityWindow("A"),
            new DurationLiteral(Duration.of(101, Duration.MICROSECOND))
        );

    assertEquivalent(expected, result);
  }

  @Test
  public void testParseAccumulatedDurationSpans() {
    final var intervalsObj = JsonNodeFactory.instance.objectNode();
    intervalsObj.put("kind", "SpansExpressionActivitySpan");
    intervalsObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "RealProfileAccumulatedDuration");
    json.set("intervalsExpression", intervalsObj);
    json.put("unit", 101);

    final var result = linearProfileExprP.parse(json).getSuccessOrThrow();

    final var expected =
        new AccumulatedDuration<>(
            new ActivitySpan("A"),
            new DurationLiteral(Duration.of(101, Duration.MICROSECOND))
        );

    assertEquivalent(expected, result);
  }

  @Test
  public void testForEachActivity() {
    final var expressionObj = JsonNodeFactory.instance.objectNode();
    expressionObj.put("kind", "WindowsExpressionActivityWindow");
    expressionObj.put("alias", "A");

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "ForEachActivityViolations");
    json.put("activityType", "TypeA");
    json.put("alias", "A");
    json.set("expression", expressionObj);
    final var result = constraintP.parse(json).getSuccessOrThrow();

    final var expected =
        new ForEachActivityViolations(
            "TypeA",
            "A",
            new ViolationsOfWindows(
                new ActivityWindow("A")));

    assertEquivalent(expected, result);
  }

  @Test
  public void testAssignGaps() {
    final var originalWindows = JsonNodeFactory.instance.objectNode();
    originalWindows.put("kind", "WindowsExpressionValue");
    originalWindows.put("value", true);

    final var defaultWindows = JsonNodeFactory.instance.objectNode();
    defaultWindows.put("kind", "WindowsExpressionValue");
    defaultWindows.put("value", false);

    var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "AssignGapsExpression");
    json.set("originalProfile", originalWindows);
    json.set("defaultProfile", defaultWindows);

    final var resultWindows = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expectedWindows = new AssignGaps<>(
        new WindowsValue(true, Optional.empty()),
        new WindowsValue(false, Optional.empty())
    );

    assertEquivalent(expectedWindows, resultWindows);

    final var originalProfile = JsonNodeFactory.instance.objectNode();
    originalProfile.put("kind", "DiscreteProfileResource");
    originalProfile.put("name", "ResA");

    final var defaultProfile = JsonNodeFactory.instance.objectNode();
    defaultProfile.put("kind", "DiscreteProfileResource");
    defaultProfile.put("name", "ResB");

    json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "AssignGapsExpression");
    json.set("originalProfile", originalProfile);
    json.set("defaultProfile", defaultProfile);

    final var resultProfile = discreteProfileExprP.parse(json).getSuccessOrThrow();

    final var expectedProfile = new AssignGaps<>(
        new DiscreteResource("ResA"),
        new DiscreteResource("ResB")
    );

    assertEquivalent(expectedProfile, resultProfile);
  }

  @Test
  public void testParseMassiveExpression() {
    // Build innermost expressions first, then compose outward

    // ResC resource
    final var resCObj = JsonNodeFactory.instance.objectNode();
    resCObj.put("kind", "RealProfileResource");
    resCObj.put("name", "ResC");

    // Times(ResC, 2.0)
    final var timesObj = JsonNodeFactory.instance.objectNode();
    timesObj.put("kind", "RealProfileTimes");
    timesObj.set("profile", resCObj);
    timesObj.put("multiplier", 2.0);

    // Parameter A.a
    final var paramAObj = JsonNodeFactory.instance.objectNode();
    paramAObj.put("kind", "RealProfileParameter");
    paramAObj.put("alias", "A");
    paramAObj.put("name", "a");

    // LessThan(Times(ResC, 2.0), Parameter(A, a))
    final var lessThanObj = JsonNodeFactory.instance.objectNode();
    lessThanObj.put("kind", "RealProfileLessThan");
    lessThanObj.set("left", timesObj);
    lessThanObj.set("right", paramAObj);

    // Not(LessThan(...))
    final var notLessThanObj = JsonNodeFactory.instance.objectNode();
    notLessThanObj.put("kind", "WindowsExpressionNot");
    notLessThanObj.set("expression", lessThanObj);

    // DiscreteProfileValue(false)
    final var discreteValueObj = JsonNodeFactory.instance.objectNode();
    discreteValueObj.put("kind", "DiscreteProfileValue");
    discreteValueObj.put("value", false);

    // DiscreteProfileParameter(B, b)
    final var paramBObj = JsonNodeFactory.instance.objectNode();
    paramBObj.put("kind", "DiscreteProfileParameter");
    paramBObj.put("alias", "B");
    paramBObj.put("name", "b");

    // Equal(DiscreteProfileValue(false), DiscreteProfileParameter(B, b))
    final var equalObj = JsonNodeFactory.instance.objectNode();
    equalObj.put("kind", "ExpressionEqual");
    equalObj.set("left", discreteValueObj);
    equalObj.set("right", paramBObj);

    // Inner Or: [Not(LessThan(...)), Equal(...)]
    final var innerOrExprs = JsonNodeFactory.instance.arrayNode();
    innerOrExprs.add(notLessThanObj);
    innerOrExprs.add(equalObj);

    final var innerOrObj = JsonNodeFactory.instance.objectNode();
    innerOrObj.put("kind", "WindowsExpressionOr");
    innerOrObj.set("expressions", innerOrExprs);

    // Not(ActivityWindow(A))
    final var actWindowA = JsonNodeFactory.instance.objectNode();
    actWindowA.put("kind", "WindowsExpressionActivityWindow");
    actWindowA.put("alias", "A");

    final var notA = JsonNodeFactory.instance.objectNode();
    notA.put("kind", "WindowsExpressionNot");
    notA.set("expression", actWindowA);

    // Not(ActivityWindow(B))
    final var actWindowB = JsonNodeFactory.instance.objectNode();
    actWindowB.put("kind", "WindowsExpressionActivityWindow");
    actWindowB.put("alias", "B");

    final var notB = JsonNodeFactory.instance.objectNode();
    notB.put("kind", "WindowsExpressionNot");
    notB.set("expression", actWindowB);

    // Outer Or: [innerOr, Not(A), Not(B)]
    final var outerOrExprs = JsonNodeFactory.instance.arrayNode();
    outerOrExprs.add(innerOrObj);
    outerOrExprs.add(notA);
    outerOrExprs.add(notB);

    final var outerOrObj = JsonNodeFactory.instance.objectNode();
    outerOrObj.put("kind", "WindowsExpressionOr");
    outerOrObj.set("expressions", outerOrExprs);

    // ForEachActivityViolations(TypeB, B, outerOr)
    final var forEachB = JsonNodeFactory.instance.objectNode();
    forEachB.put("kind", "ForEachActivityViolations");
    forEachB.put("activityType", "TypeB");
    forEachB.put("alias", "B");
    forEachB.set("expression", outerOrObj);

    // ForEachActivityViolations(TypeA, A, forEachB)
    var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "ForEachActivityViolations");
    json.put("activityType", "TypeA");
    json.put("alias", "A");
    json.set("expression", forEachB);

    final var result = constraintP.parse(json).getSuccessOrThrow();

    final var expected = new ForEachActivityViolations(
        "TypeA",
        "A",
        new ForEachActivityViolations(
            "TypeB",
            "B",
            new ViolationsOfWindows(
                new Or(
                    new Or(
                        new Not(
                            new LessThan(
                                new Times(
                                    new RealResource("ResC"),
                                    2),
                                new RealParameter("A", "a"))),
                        new Equal<>(
                            new DiscreteValue(SerializedValue.of(false), Optional.empty()),
                            new DiscreteParameter("B", "b"))),
                    new Not(new ActivityWindow("A")),
                    new Not(new ActivityWindow("B"))))));

    assertEquivalent(expected, result);
  }

  // I know its excessive, I just wanted to be sure :)
  @Test
  public void testDeepMutualRecursion() {
    // Build from innermost to outermost
    final var actWindow = JsonNodeFactory.instance.objectNode();
    actWindow.put("kind", "WindowsExpressionActivityWindow");
    actWindow.put("alias", "TEST");

    // Layer 1 (innermost SpansFromWindows)
    final var sfwLayer1 = JsonNodeFactory.instance.objectNode();
    sfwLayer1.put("kind", "SpansExpressionFromWindows");
    sfwLayer1.set("windowsExpression", actWindow);

    final var wfsLayer1 = JsonNodeFactory.instance.objectNode();
    wfsLayer1.put("kind", "WindowsExpressionFromSpans");
    wfsLayer1.set("spansExpression", sfwLayer1);

    // Layer 2
    final var sfwLayer2 = JsonNodeFactory.instance.objectNode();
    sfwLayer2.put("kind", "SpansExpressionFromWindows");
    sfwLayer2.set("windowsExpression", wfsLayer1);

    final var wfsLayer2 = JsonNodeFactory.instance.objectNode();
    wfsLayer2.put("kind", "WindowsExpressionFromSpans");
    wfsLayer2.set("spansExpression", sfwLayer2);

    // Layer 3
    final var sfwLayer3 = JsonNodeFactory.instance.objectNode();
    sfwLayer3.put("kind", "SpansExpressionFromWindows");
    sfwLayer3.set("windowsExpression", wfsLayer2);

    final var wfsLayer3 = JsonNodeFactory.instance.objectNode();
    wfsLayer3.put("kind", "WindowsExpressionFromSpans");
    wfsLayer3.set("spansExpression", sfwLayer3);

    // Layer 4
    final var sfwLayer4 = JsonNodeFactory.instance.objectNode();
    sfwLayer4.put("kind", "SpansExpressionFromWindows");
    sfwLayer4.set("windowsExpression", wfsLayer3);

    final var wfsLayer4 = JsonNodeFactory.instance.objectNode();
    wfsLayer4.put("kind", "WindowsExpressionFromSpans");
    wfsLayer4.set("spansExpression", sfwLayer4);

    // Layer 5
    final var sfwLayer5 = JsonNodeFactory.instance.objectNode();
    sfwLayer5.put("kind", "SpansExpressionFromWindows");
    sfwLayer5.set("windowsExpression", wfsLayer4);

    final var wfsLayer5 = JsonNodeFactory.instance.objectNode();
    wfsLayer5.put("kind", "WindowsExpressionFromSpans");
    wfsLayer5.set("spansExpression", sfwLayer5);

    // Layer 6 (outermost)
    final var sfwLayer6 = JsonNodeFactory.instance.objectNode();
    sfwLayer6.put("kind", "SpansExpressionFromWindows");
    sfwLayer6.set("windowsExpression", wfsLayer5);

    final var json = JsonNodeFactory.instance.objectNode();
    json.put("kind", "WindowsExpressionFromSpans");
    json.set("spansExpression", sfwLayer6);

    final var result = windowsExpressionP.parse(json).getSuccessOrThrow();

    final var expected = new WindowsFromSpans(
        new SpansFromWindows(
            new WindowsFromSpans(
                new SpansFromWindows(
                    new WindowsFromSpans(
                        new SpansFromWindows(
                            new WindowsFromSpans(
                                new SpansFromWindows(
                                    new WindowsFromSpans(
                                        new SpansFromWindows(
                                            new WindowsFromSpans(
                                                new SpansFromWindows(
                                                    new ActivityWindow("TEST")
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    );

    assertEquivalent(expected, result);
  }
}
