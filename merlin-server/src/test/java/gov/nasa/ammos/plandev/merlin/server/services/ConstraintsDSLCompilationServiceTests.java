package gov.nasa.jpl.aerie.merlin.server.services;

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
import gov.nasa.jpl.aerie.constraints.tree.Ends;
import gov.nasa.jpl.aerie.constraints.tree.Equal;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivitySpans;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivityViolations;
import gov.nasa.jpl.aerie.constraints.tree.GreaterThan;
import gov.nasa.jpl.aerie.constraints.tree.GreaterThanOrEqual;
import gov.nasa.jpl.aerie.constraints.tree.KeepTrueSegment;
import gov.nasa.jpl.aerie.constraints.tree.LessThan;
import gov.nasa.jpl.aerie.constraints.tree.LessThanOrEqual;
import gov.nasa.jpl.aerie.constraints.tree.LongerThan;
import gov.nasa.jpl.aerie.constraints.tree.Not;
import gov.nasa.jpl.aerie.constraints.tree.NotEqual;
import gov.nasa.jpl.aerie.constraints.tree.Or;
import gov.nasa.jpl.aerie.constraints.tree.Plus;
import gov.nasa.jpl.aerie.constraints.tree.ProfileExpression;
import gov.nasa.jpl.aerie.constraints.tree.Rate;
import gov.nasa.jpl.aerie.constraints.tree.RealParameter;
import gov.nasa.jpl.aerie.constraints.tree.RealResource;
import gov.nasa.jpl.aerie.constraints.tree.RealValue;
import gov.nasa.jpl.aerie.constraints.tree.RollingThreshold;
import gov.nasa.jpl.aerie.constraints.tree.ShiftBy;
import gov.nasa.jpl.aerie.constraints.tree.ShiftEdges;
import gov.nasa.jpl.aerie.constraints.tree.ShorterThan;
import gov.nasa.jpl.aerie.constraints.tree.SpansConnectTo;
import gov.nasa.jpl.aerie.constraints.tree.SpansContains;
import gov.nasa.jpl.aerie.constraints.tree.SpansFromWindows;
import gov.nasa.jpl.aerie.constraints.tree.SpansSelectWhenTrue;
import gov.nasa.jpl.aerie.constraints.tree.Split;
import gov.nasa.jpl.aerie.constraints.tree.Starts;
import gov.nasa.jpl.aerie.constraints.tree.Times;
import gov.nasa.jpl.aerie.constraints.tree.Transition;
import gov.nasa.jpl.aerie.constraints.tree.ValueAt;
import gov.nasa.jpl.aerie.constraints.tree.ViolationsOfWindows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsFromSpans;
import gov.nasa.jpl.aerie.constraints.tree.WindowsValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.mocks.StubMissionModelService;
import gov.nasa.jpl.aerie.merlin.server.mocks.StubPlanService;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.constraints.tree.RollingThreshold.RollingThresholdAlgorithm.DeficitHull;
import static gov.nasa.jpl.aerie.constraints.tree.RollingThreshold.RollingThresholdAlgorithm.DeficitSpans;
import static gov.nasa.jpl.aerie.constraints.tree.RollingThreshold.RollingThresholdAlgorithm.ExcessHull;
import static gov.nasa.jpl.aerie.constraints.tree.RollingThreshold.RollingThresholdAlgorithm.ExcessSpans;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstraintsDSLCompilationServiceTests {
  private static final MissionModelId MISSION_MODEL_ID = new MissionModelId(1L);
  private static final PlanId PLAN_ID = new PlanId(1L);
  ConstraintsDSLCompilationService constraintsDSLCompilationService;

  @BeforeAll
  void setUp() throws IOException {
    constraintsDSLCompilationService = new ConstraintsDSLCompilationService(
        new TypescriptCodeGenerationServiceAdapter(new StubMissionModelService(), new StubPlanService())
    );
  }

  @AfterAll
  void tearDown() {
    constraintsDSLCompilationService.close();
  }

  private <T> void checkSuccessfulCompilation(String constraint, Expression<T> expected)
  {
    final ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult result;
    result = assertDoesNotThrow(() -> constraintsDSLCompilationService.compileConstraintsDSL(MISSION_MODEL_ID, Optional.of(PLAN_ID), Optional.empty(), constraint));
    if (result instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Success r) {
      assertEquals(expected, r.constraintExpression());
    } else if (result instanceof ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error r) {
      fail(r.toString());
    }
  }

  private void checkFailedCompilation(String constraint, String error) {
    final ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error actualErrors;
    actualErrors = (ConstraintsDSLCompilationService.ConstraintsDSLCompilationResult.Error) assertDoesNotThrow(() -> constraintsDSLCompilationService.compileConstraintsDSL(
        MISSION_MODEL_ID, Optional.of(PLAN_ID), Optional.empty(), constraint
    ));
    if (actualErrors.errors()
                    .stream()
                    .noneMatch(e -> e.getMessage().contains(error))) {
      fail("Expected error:\n" + error + "\nIn list of errors:\n" + actualErrors.errors() + "\n");
    }
  }

  @Test
  void testConstraintsDSL_helper_function()
  {
    checkSuccessfulCompilation(
      """
        export default function myConstraint() {
          return times2(Real.Resource("state of charge")).changes()
        }
        function times2(e: Real): Real {
          return e.times(2)
        }
      """,
      new ViolationsOfWindows(
          new Changes<>(new ProfileExpression<>(new Times(new RealResource("state of charge"), 2.0)))
      )
    );
  }

  @Test
  void testConstraintDSL_during(){
    checkSuccessfulCompilation(
        """
          export default () => {
              return Windows.During(ActivityType.activity)
          }
        """,
        new ViolationsOfWindows(new Or(new WindowsFromSpans(new ForEachActivitySpans("activity", "span activity alias 0", new ActivitySpan("span activity alias 0")))))
    );
  }

  @Test
  void testConstraintsDSL_variable_not_defined() {
    checkFailedCompilation(
        """
          export default function myConstraint() {
            const x = 5;
            return times2(Real.Resource("mode")).changes()
          }
          function times2(e: Real): Real {
            return e.times(x)
          }
        """,
       "TypeError: TS2304 Cannot find name 'x'."
    );
  }

  @Test
  void testConstraintsDSL_wrong_return_type() {
    checkFailedCompilation(
        """
          export default function myConstraint() {
             return Real.Resource("state of charge");
          }
        """,
        "TypeError: TS2322 Incorrect return type. Expected: 'Constraint | Promise<Constraint>', Actual: 'Real'."
    );
  }

  //// TESTS FOR `Discrete` CLASS API

  @Test
  void testDiscreteResource() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Discrete.Resource("mode").changes();
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new DiscreteResource("mode"))))
    );
  }

  @Test
  void testDiscreteValue() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Discrete.Value(5).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new DiscreteValue(SerializedValue.of(5)))))
    );
  }

  @Test
  void testDiscreteValueInterval() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Discrete.Value(5, Interval.Between(
                Temporal.Instant.fromEpochMilliseconds(1574074321816),
                Temporal.Instant.fromEpochMilliseconds(1574074322816)
              )).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new DiscreteValue(
            SerializedValue.of(5),
            Optional.of(new AbsoluteInterval(Optional.of(Instant.parse("2019-11-18T10:52:01.816Z")), Optional.of(Instant.parse("2019-11-18T10:52:02.816Z")), Optional.empty(), Optional.empty()))
        ))))
    );
  }

  @Test
  void testValueAt() {
    checkSuccessfulCompilation(
        """
            import { ActivityInstance } from './constraints-edsl-fluent-api.js';
            export default () => {
              return Discrete.Resource("mode").valueAt(new ActivityInstance(ActivityType.activity, "alias1").span().starts()).notEqual("Option1")
            }
        """,
        new ViolationsOfWindows(new NotEqual<>(
            new ValueAt<>(
                new ProfileExpression<>(new DiscreteResource("mode")),
                new Starts<>(new ActivitySpan("alias1"))),
            new DiscreteValue(SerializedValue.of("Option1")))));
  }


  @Test
  void testDiscreteParameter() {
    checkSuccessfulCompilation(
        """
            export default () => Constraint.ForEachActivity(
              ActivityType.activity,
              (instance) => instance.parameters.Param.changes()
            )
        """,
        new ForEachActivityViolations(
            "activity",
            "activity alias 0",
            new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new DiscreteParameter("activity alias 0", "Param"))))
        )
    );
  }

  @Test
  void testTransition() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Discrete.Resource("mode").transition("Option1", "Option2");
            }
        """,
        new ViolationsOfWindows(
            new Transition(
                new DiscreteResource("mode"),
                SerializedValue.of("Option1"),
                SerializedValue.of("Option2")
            )
        )
    );
  }

  @Test
  void testTransitionMismatchedTypes() {
    checkFailedCompilation(
        """
          export default () => {
            return Discrete.Resource("mode").transition("something else", 5);
          }
        """,
        "TS2345 Argument of type '\"something else\"' is not assignable to parameter of type '\"Option1\" | \"Option2\"'."
    );
  }

  @Test
  void testDiscreteEqual() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Discrete.Resource("mode").equal("Option2");
        }
        """,
        new ViolationsOfWindows(new Equal<>(new DiscreteResource("mode"), new DiscreteValue(SerializedValue.of("Option2"))))
    );
  }

  @Test
  void testDiscreteNotEqual() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Discrete.Resource("an integer").notEqual(4.0);
        }
        """,
        new ViolationsOfWindows(new NotEqual<>(new DiscreteResource("an integer"), new DiscreteValue(SerializedValue.of(4))))
    );
  }

  @Test
  void testDiscreteChanges() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Discrete.Value(4).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new DiscreteValue(SerializedValue.of(4)))))
    );
  }

  @Test
  void testAutomaticWrapAnyInDiscreteValue() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Discrete.Resource("mode").equal("Option1")
          }
        """,
        new ViolationsOfWindows(new Equal<>(new DiscreteResource("mode"), new DiscreteValue(SerializedValue.of("Option1"))))
    );
  }

  @Test
  void testDiscreteAssignGaps() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Discrete.Resource("mode").assignGaps("Option1").equal("Option1");
          }
        """,
        new ViolationsOfWindows(
            new Equal<>(
                new AssignGaps<>(
                    new DiscreteResource("mode"),
                    new DiscreteValue(SerializedValue.of("Option1"))
                ),
                new DiscreteValue(SerializedValue.of("Option1"))
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Discrete.Resource("mode").assignGaps(Discrete.Resource("mode")).equal("Option1");
          }
        """,
        new ViolationsOfWindows(new Equal<>(new AssignGaps<>(new DiscreteResource("mode"), new DiscreteResource("mode")), new DiscreteValue(SerializedValue.of("Option1"))))
    );
  }

  //// TESTS FOR `Real` CLASS API

  @Test
  void testRealResource() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Resource("state of charge").changes();
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealResource("state of charge"))))
    );
  }

  @Test
  void testRealValue() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Value(5).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealValue(5.0))))
    );
  }

  @Test
  void testRealValueInterval() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Value(5, 3, Interval.Between(
                Temporal.Instant.from("2019-11-18T10:52:01.816Z"),
                Temporal.Instant.from("2019-11-18T10:52:02.816Z")
              )).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealValue(
            5, 3,
            Optional.of(new AbsoluteInterval(Optional.of(Instant.parse("2019-11-18T10:52:01.816Z")), Optional.of(Instant.parse("2019-11-18T10:52:02.816Z")), Optional.empty(), Optional.empty()))
        ))))
    );

    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Value(5, 3, Interval.Between(
                Temporal.Instant.from("2019-11-18T10:52:01.816Z"),
                Temporal.Instant.from("2019-11-18T10:52:02.816Z"),
                Inclusivity.Exclusive,
                Inclusivity.Inclusive
              )).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealValue(
            5, 3,
            Optional.of(new AbsoluteInterval(Optional.of(Instant.parse("2019-11-18T10:52:01.816Z")), Optional.of(Instant.parse("2019-11-18T10:52:02.816Z")), Optional.of(
                Interval.Inclusivity.Exclusive), Optional.of(Interval.Inclusivity.Inclusive)))
        ))))
    );

    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Value(5, 3, Interval.At(
                Temporal.Instant.from("2019-11-18T10:52:01.816Z")
              )).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealValue(
            5, 3,
            Optional.of(new AbsoluteInterval(Optional.of(Instant.parse("2019-11-18T10:52:01.816Z")), Optional.of(Instant.parse("2019-11-18T10:52:01.816Z")), Optional.empty(), Optional.empty()))
        ))))
    );

    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Value(5, 3, Interval.Horizon()).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealValue(
            5, 3,
            Optional.of(new AbsoluteInterval(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
        ))))
    );
  }

  @Test
  void testRealParameter() {
    checkSuccessfulCompilation(
        """
            export default () => Constraint.ForEachActivity(
              ActivityType.activity,
              (instance) => instance.parameters.AnotherParam.changes()
            )
        """,
        new ForEachActivityViolations(
            "activity",
            "activity alias 0",
            new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealParameter("activity alias 0", "AnotherParam"))))
        )
    );
  }

  @Test
  void testRate() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").rate().equal(Real.Value(4.0))
            }
        """,
        new ViolationsOfWindows(new Equal<>(new Rate(new RealResource("state of charge")), new RealValue(4.0)))
    );
  }

  @Test
  void testLongerThan() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").rate().equal(Real.Value(4.0)).longerThan(Temporal.Duration.from({seconds: 1}));
            }
        """,
        new ViolationsOfWindows(new LongerThan(new Equal<>(new Rate(new RealResource("state of charge")), new RealValue(4.0)), new DurationLiteral(Duration.of(1000, Duration.MILLISECONDS))))
    );
  }

  @Test
  void testShorterThan() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").rate().equal(Real.Value(4.0)).shorterThan(Temporal.Duration.from({hours: 2}));
            }
        """,
        new ViolationsOfWindows(new ShorterThan(new Equal<>(new Rate(new RealResource("state of charge")), new RealValue(4.0)), new DurationLiteral(Duration.of(2, Duration.HOURS))))
    );
  }

  @Test
  void testShiftWindowsEdges() {
    checkSuccessfulCompilation(
        """
            const minute = (m: number) => Temporal.Duration.from({minutes: m});
            export default() => {
              return Real.Resource("state of charge").rate().equal(Real.Value(4.0)).shiftBy(minute(2), minute(-20))
            }
        """,
        new ViolationsOfWindows(new ShiftEdges<>(
            new Equal<>(new Rate(new RealResource("state of charge")), new RealValue(4.0)),
            new DurationLiteral(Duration.of(2, Duration.MINUTE)),
            new DurationLiteral(Duration.of(-20, Duration.MINUTE)))
        )
    );
  }

  @Test
  void testTimes() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").times(2).equal(Real.Value(4.0))
            }
        """,
        new ViolationsOfWindows(new Equal<>(new Times(new RealResource("state of charge"), 2.0), new RealValue(4.0)))
    );
  }

  @Test
  void testPlus() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").plus(Real.Value(2.0)).equal(Real.Value(4.0))
            }
        """,
        new ViolationsOfWindows(new Equal<>(new Plus(new RealResource("state of charge"), new RealValue(2.0)), new RealValue(4.0)))
    );
  }

  @Test
  void testNegate() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").negate().equal(Real.Value(4.0))
            }
        """,
        new ViolationsOfWindows(new Equal<>(new Times(new RealResource("state of charge"), -1.0), new RealValue(4.0)))
    );
  }

  @Test
  void testMinus() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").minus(2.0).equal(4.0)
            }
        """,
        new ViolationsOfWindows(new Equal<>(new Plus(new RealResource("state of charge"), new RealValue(-2.0)), new RealValue(4.0)))
    );

    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").minus(Real.Resource("state of charge")).equal(4.0)
            }
        """,
        new ViolationsOfWindows(new Equal<>(new Plus(new RealResource("state of charge"), new Times(new RealResource("state of charge"), -1.0)), new RealValue(4.0)))
    );
  }

  @Test
  void testLessThan() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").lessThan(Real.Value(2.0))
            }
        """,
        new ViolationsOfWindows(new LessThan(new RealResource("state of charge"), new RealValue(2.0)))
    );
  }

  @Test
  void testLessThanOrEqual() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").lessThanOrEqual(Real.Value(2.0))
            }
        """,
        new ViolationsOfWindows(new LessThanOrEqual(new RealResource("state of charge"), new RealValue(2.0)))
    );
  }

  @Test
  void testGreaterThan() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").greaterThan(Real.Value(2.0))
            }
        """,
        new ViolationsOfWindows(new GreaterThan(new RealResource("state of charge"), new RealValue(2.0)))
    );
  }

  @Test
  void testGreaterThanOrEqual() {
    checkSuccessfulCompilation(
        """
            export default() => {
              return Real.Resource("state of charge").greaterThanOrEqual(Real.Value(2.0))
            }
        """,
        new ViolationsOfWindows(new GreaterThanOrEqual(new RealResource("state of charge"), new RealValue(2.0)))
    );
  }

  @Test
  void testRealEqual() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Real.Resource("state of charge").equal(Real.Value(-1));
        }
        """,
        new ViolationsOfWindows(new Equal<>(new RealResource("state of charge"), new RealValue(-1.0)))
    );
  }

  @Test
  void testRealNotEqual() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Real.Resource("an integer").notEqual(Real.Value(-1));
        }
        """,
        new ViolationsOfWindows(new NotEqual<>(new RealResource("an integer"), new RealValue(-1.0)))
    );
  }

  @Test
  void testRealIsWithin() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Real.Resource("an integer").isWithin(Real.Value(10), Real.Value(1));
        }
        """,
        new ViolationsOfWindows(
            new And(
                java.util.List.of(
                    new LessThanOrEqual(new RealResource("an integer"), new Plus(new RealValue(10), new RealValue(1))),
                    new GreaterThanOrEqual(new RealResource("an integer"), new Plus(new RealValue(10), new Times(new RealValue(1), -1)))
                )
            )
        )
    );
  }

  @Test
  void testRealChanges() {
    checkSuccessfulCompilation(
        """
            export default () => {
              return Real.Value(4).changes()
            }
        """,
        new ViolationsOfWindows(new Changes<>(new ProfileExpression<>(new RealValue(4.0))))
    );
  }

  @Test
  void testAutomaticWrapNumberInRealValue() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Value(2.2).plus(-3).lessThan(5);
          }
        """,
        new ViolationsOfWindows(new LessThan(new Plus(new RealValue(2.2), new RealValue(-3.0)), new RealValue(5.0)))
    );
  }

  @Test
  void testRealAssignGaps() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("an integer").assignGaps(0).lessThan(5);
          }
        """,
        new ViolationsOfWindows(new LessThan(new AssignGaps<>(new RealResource("an integer"), new RealValue(0.0)), new RealValue(5.0)))
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("an integer").assignGaps(Real.Resource("an integer")).lessThan(5);
          }
        """,
        new ViolationsOfWindows(new LessThan(new AssignGaps<>(new RealResource("an integer"), new RealResource("an integer")), new RealValue(5.0)))
    );
  }

  //// TESTS FOR `Windows` CLASS API

  @Test
  void testDuring() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Constraint.ForEachActivity(ActivityType.activity, (alias) => alias.window());
          }
        """,
        new ForEachActivityViolations("activity", "activity alias 0", new ViolationsOfWindows(new ActivityWindow("activity alias 0")))
    );
  }

  @Test
  void testStartOf() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Constraint.ForEachActivity(ActivityType.activity, (alias) => alias.start().windows());
          }
        """,
        new ForEachActivityViolations("activity", "activity alias 0", new ViolationsOfWindows(new WindowsFromSpans(new Starts<>(new ActivitySpan("activity alias 0")))))
    );
  }

  @Test
  void testEndOf() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Constraint.ForEachActivity(ActivityType.activity, (alias) => alias.end().windows());
          }
        """,
        new ForEachActivityViolations("activity", "activity alias 0", new ViolationsOfWindows(new WindowsFromSpans(new Ends<>(new ActivitySpan("activity alias 0")))))
    );
  }

  @Test
  void testIf() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(2)
              .if(Discrete.Resource("mode").changes());
          }
        """,
        new ViolationsOfWindows(
            new Or(
                new Not(new Changes<>(
                    new ProfileExpression<>(new DiscreteResource("mode"))
                )),
                new LessThan(new RealResource("state of charge"), new RealValue(2.0))
            )
        )
    );
  }

  @Test
  void testAnd() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Windows.And(
              Real.Resource("state of charge").lessThan(2),
              Discrete.Value("hello there").notEqual(Discrete.Value("hello there")),
              Real.Value(5).changes()
            );
          }
        """,
        new ViolationsOfWindows(
            new And(
                java.util.List.of(
                    new LessThan(new RealResource("state of charge"), new RealValue(2.0)),
                    new NotEqual<>(new DiscreteValue(SerializedValue.of("hello there")), new DiscreteValue(SerializedValue.of("hello there"))),
                    new Changes<>(new ProfileExpression<>(new RealValue(5.0)))
                )
            )
        )
    );
  }

  @Test
  void testOr() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Windows.Or(
              Real.Resource("state of charge").lessThan(2),
              Discrete.Value("hello there").notEqual(Discrete.Value("hello there")),
              Real.Value(5).changes()
            );
          }
        """,
        new ViolationsOfWindows(
            new Or(
                java.util.List.of(
                    new LessThan(new RealResource("state of charge"), new RealValue(2.0)),
                    new NotEqual<>(new DiscreteValue(SerializedValue.of("hello there")), new DiscreteValue(SerializedValue.of("hello there"))),
                    new Changes<>(new ProfileExpression<>(new RealValue(5.0)))
                )
            )
        )
    );
  }

  @Test
  void testNot() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Discrete.Resource("mode").changes().not()
          }
        """,
        new ViolationsOfWindows(
            new Not(
                new Changes<>(new ProfileExpression<>(new DiscreteResource("mode")))
            )
        )
    );
  }

  @Test
  void testStarts() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).starts()
          }
        """,
        new ViolationsOfWindows(
            new Starts<>(new LessThan(new RealResource("state of charge"), new RealValue(0.3)))
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).spans().starts().windows()
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(new Starts<>(new SpansFromWindows(new LessThan(new RealResource("state of charge"), new RealValue(0.3)))))
        )
    );
  }
@Test
  void testKeepTrueSegment() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).starts().keepTrueSegment(2)
          }
        """,
        new ViolationsOfWindows(
            new KeepTrueSegment(new Starts<>(new LessThan(new RealResource("state of charge"), new RealValue(0.3))), 2)
        )
    );
  }

  @Test
  void testEnds() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).ends()
          }
        """,
        new ViolationsOfWindows(
            new Ends<>(new LessThan(new RealResource("state of charge"), new RealValue(0.3)))
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).spans().ends().windows()
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(new Ends<>(new SpansFromWindows(new LessThan(new RealResource("state of charge"), new RealValue(0.3)))))
        )
    );
  }

  @Test
  void testSplit() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).split(4).windows()
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(
              new Split<>(
                  new LessThan(new RealResource("state of charge"), new RealValue(0.3)),
                  4,
                  Interval.Inclusivity.Inclusive,
                  Interval.Inclusivity.Exclusive
              )
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).spans().split(4).windows()
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(
              new Split<>(
                  new SpansFromWindows(new LessThan(new RealResource("state of charge"), new RealValue(0.3))),
                  4,
                  Interval.Inclusivity.Inclusive,
                  Interval.Inclusivity.Exclusive
              )
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).split(4, Inclusivity.Exclusive, Inclusivity.Inclusive).windows()
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(
                new Split<>(
                    new LessThan(new RealResource("state of charge"), new RealValue(0.3)),
                    4,
                    Interval.Inclusivity.Exclusive,
                    Interval.Inclusivity.Inclusive
                )
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).spans().split(4, Inclusivity.Exclusive, Inclusivity.Exclusive).windows()
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(
                new Split<>(
                    new SpansFromWindows(new LessThan(new RealResource("state of charge"), new RealValue(0.3))),
                    4,
                    Interval.Inclusivity.Exclusive,
                    Interval.Inclusivity.Exclusive
                )
            )
        )
    );
  }

  @Test
  void testSplitArgumentError() {
    checkFailedCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).split(0).windows()
          }
        """,
        ".split numberOfSubSpans cannot be less than 1, but was: 0"
    );

    checkFailedCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).split(-2).windows()
          }
        """,
        ".split numberOfSubSpans cannot be less than 1, but was: -2"
    );

    checkFailedCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).spans().split(0).windows()
          }
        """,
        ".split numberOfSubSpans cannot be less than 1, but was: 0"
    );

    checkFailedCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").lessThan(0.3).spans().split(-2).windows()
          }
        """,
        ".split numberOfSubSpans cannot be less than 1, but was: -2"
    );
  }

  @Test
  void testAccumulatedDuration() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("state of charge").equal(4)
              .accumulatedDuration(Temporal.Duration.from({minutes: 1}))
              .lessThan(5);
          }
        """,
        new ViolationsOfWindows(
            new LessThan(
                new AccumulatedDuration<>(new Equal<>(new RealResource("state of charge"), new RealValue(4.0)), new DurationLiteral(Duration.MINUTE)),
                new RealValue(5.0)
            )
        )
    );
  }

  @Test
  void testViolations() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Real.Resource("state of charge").equal(Real.Value(-1)).violations();
        }
        """,
        new ViolationsOfWindows(new Equal<>(new RealResource("state of charge"), new RealValue(-1.0)))
    );
  }

  @Test
  void testWindowsValue() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Windows.Value(false);
          }
        """,
        new ViolationsOfWindows(new WindowsValue(false))
    );
  }

  @Test
  void testWindowsAssignGaps() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Real.Resource("an integer").lessThan(5).assignGaps(false);
          }
        """,
        new ViolationsOfWindows(new AssignGaps<>(new LessThan(new RealResource("an integer"), new RealValue(5.0)), new WindowsValue(false)))
    );
  }

  //// TESTS FOR `Spans` API CLASS

  @Test
  void testSpansFromWindows() {
    checkSuccessfulCompilation(
        """
            export default () => {
                return Real.Resource("state of charge").equal(0.3).spans().windows();
            }
        """,
        new ViolationsOfWindows(new WindowsFromSpans(new SpansFromWindows(new Equal<>(new RealResource("state of charge"), new RealValue(0.3)))))
    );
  }

  //// TESTS FOR `Constraint` API CLASS

  @Test
  void testForbiddenActivityOverlap() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Constraint.ForbiddenActivityOverlap(ActivityType.activity, ActivityType.activity)
        }
        """,
        new ForEachActivityViolations(
            "activity",
            "activity alias 0",
            new ForEachActivityViolations(
                "activity",
                "activity alias 1",
                new ViolationsOfWindows(new Not(new And(new ActivityWindow("activity alias 0"), new ActivityWindow("activity alias 1"))))
            )
        )
    );
  }

  @Test
  void testForEachActivity() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Constraint.ForEachActivity(
            ActivityType.activity,
            (myAlias) => myAlias.window()
          )
        }
        """,
        new ForEachActivityViolations("activity", "activity alias 0", new ViolationsOfWindows(new ActivityWindow("activity alias 0")))
    );

    checkSuccessfulCompilation(
        """
        import { ActivityInstance } from './constraints-edsl-fluent-api.js';
        export default () => {
          return Constraint.ForEachActivity(
            ActivityType.activity,
            myHelperFunction
          )
        }

        function myHelperFunction(instance: ActivityInstance<ActivityType.activity>): Constraint {
          return instance.window();
        }
        """,
        new ForEachActivityViolations("activity", "activity alias 0", new ViolationsOfWindows(new ActivityWindow("activity alias 0")))
    );
  }

  @Test
  void testNestedForEachActivity() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Constraint.ForEachActivity(
            ActivityType.activity,
            (alias1) => Constraint.ForEachActivity(
              ActivityType.activity,
              (alias2) => Windows.And(alias1.window(), alias2.window())
            )
          )
        }
        """,
        new ForEachActivityViolations("activity", "activity alias 0", new ForEachActivityViolations("activity", "activity alias 1", new ViolationsOfWindows(
            new And(new ActivityWindow("activity alias 0"), new ActivityWindow("activity alias 1"))
        )))
    );
  }

  @Test
  void testDuringedsl() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Windows.During(ActivityType.activity)
        }
        """,
        new ViolationsOfWindows(
            new Or(
              new WindowsFromSpans(
                      new ForEachActivitySpans(
                          "activity",
                          "span activity alias 0",
                          new ActivitySpan("span activity alias 0")
                      )
              )
            )
        )
    );
  }

  @Test
  void testDuringAlledsl() {
    checkSuccessfulCompilation(
        """
        export default () => {
          return Windows.During(ActivityType.activity, ActivityType.activity2)
        }
        """,
        new ViolationsOfWindows(
            new Or(
              new WindowsFromSpans(
                  new ForEachActivitySpans(
                      "activity",
                      "span activity alias 0",
                      new ActivitySpan("span activity alias 0"))
              ),
              new WindowsFromSpans(
                  new ForEachActivitySpans(
                      "activity2",
                      "span activity alias 1",
                      new ActivitySpan("span activity alias 1"))
              )
            )
        )
    );
  }

  // TYPECHECKING FAILURE TESTS

  @Test
  void testWrongActivityType() {
    checkFailedCompilation(
        """
        export default () => {
          return Constraint.ForbiddenActivityOverlap(
            ActivityType.activity,
            "other activity"
          );
        }
        """,
        "TypeError: TS2345 Argument of type '\"other activity\"' is not assignable to parameter of type 'ActivityType'."
    );
  }

  @Test
  void testWrongResource() {
    checkFailedCompilation(
        """
        export default () => {
          return Discrete.Resource("wrong resource").changes()
        }
        """,
        "TypeError: TS2345 Argument of type '\"wrong resource\"' is not assignable to parameter of type 'ResourceName'."
    );
  }

  @Test
  void testWrongRealResource() {
    checkFailedCompilation(
        """
        export default () => {
          return Real.Resource("mode").changes()
        }
        """,
        "TypeError: TS2345 Argument of type '\"mode\"' is not assignable to parameter of type 'RealResourceName'."
    );
  }

  @Test
  void testWrongDiscreteSchema() {
    checkFailedCompilation(
        """
        export default () => {
          return Discrete.Resource("mode").equal(5)
        }
        """,
        "TypeError: TS2345 Argument of type '5' is not assignable to parameter of type '\"Option1\" | \"Option2\" | Discrete<\"Option1\" | \"Option2\">'."
    );

    checkFailedCompilation(
        """
        export default () => {
          return Discrete.Resource("mode").equal(Discrete.Resource("state of charge"))
        }
        """,
        "TypeError: TS2345 Argument of type 'Discrete<{ initial: number; rate: number; }>' is not assignable to parameter of type '\"Option1\" | \"Option2\" | Discrete<\"Option1\" | \"Option2\">'."
    );

    checkFailedCompilation(
        """
        export default () => {
          return Constraint.ForEachActivity(
            ActivityType.activity,
            (alias) => Discrete.Resource("mode").equal(alias.parameters.Param)
          )
        }
        """,
        "TypeError: TS2345 Argument of type 'Discrete<string>' is not assignable to parameter of type '\"Option1\" | \"Option2\" | Discrete<\"Option1\" | \"Option2\">'."
    );
  }

  @Test
  void testProfileShiftBy() {
    checkSuccessfulCompilation(
        """
        const minute = (m: number) => Temporal.Duration.from({minutes: m});
        export default() => {
          return Real.Resource("state of charge").shiftBy(minute(2)).equal(Real.Value(4.0))
        }
        """,
        new ViolationsOfWindows(
            new Equal<>(new ShiftBy<>(new RealResource("state of charge"), new DurationLiteral(Duration.of(2, Duration.MINUTE))), new RealValue(4.0))
        )
    );

    checkSuccessfulCompilation(
        """
        const minute = (m: number) => Temporal.Duration.from({minutes: m});
        export default() => {
          return Discrete.Resource("mode").shiftBy(minute(2)).equal("Option1")
        }
        """,
        new ViolationsOfWindows(
            new Equal<>(new ShiftBy<>(new DiscreteResource("mode"), new DurationLiteral(Duration.of(2, Duration.MINUTE))), new DiscreteValue(SerializedValue.of("Option1")))
        )
    );
  }

  @Test
  void testRollingThreshold() {
    final var algs = Map.of(
        "ExcessHull", ExcessHull,
        "ExcessSpans", ExcessSpans,
        "DeficitHull", DeficitHull,
        "DeficitSpans", DeficitSpans
    );
    for (final var entry: algs.entrySet()) {
      checkSuccessfulCompilation(
          """
              export default () => {
                return Constraint.RollingThreshold(
                  Spans.ForEachActivity(ActivityType.activity),
                  Temporal.Duration.from({hours: 1}),
                  Temporal.Duration.from({minutes: 5}),
                  RollingThresholdAlgorithm.%s
                );
              }
              """.formatted(entry.getKey()),
          new RollingThreshold(
              new ForEachActivitySpans(
                  "activity",
                  "span activity alias 0",
                  new ActivitySpan("span activity alias 0")
              ),
              new DurationLiteral(Duration.of(1, Duration.HOUR)),
              new DurationLiteral(Duration.of(5, Duration.MINUTE)),
              entry.getValue()
          )
      );
    }
  }

  @Test
  void testSpansShiftBy() {
    checkSuccessfulCompilation(
        """
        const minute = (m: number) => Temporal.Duration.from({minutes: m});
        export default() => {
          return Spans.ForEachActivity(ActivityType.activity, i => i.span()).shiftBy(minute(2)).windows();
        }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(new ShiftEdges<>(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")),
                new DurationLiteral(Duration.of(2, Duration.MINUTE)),
                new DurationLiteral(Duration.of(2, Duration.MINUTE))))
        )
    );

    checkSuccessfulCompilation(
        """
        const minute = (m: number) => Temporal.Duration.from({minutes: m});
        export default() => {
          return Spans.ForEachActivity(ActivityType.activity, i => i.span()).shiftBy(minute(2), minute(3)).windows();
        }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(new ShiftEdges<>(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")),
                new DurationLiteral(Duration.of(2, Duration.MINUTE)),
                new DurationLiteral(Duration.of(3, Duration.MINUTE))
            ))
        )
    );
  }

  @Test
  void testSpansSelectWhenTrue() {
    checkSuccessfulCompilation(
        """
        const minute = (m: number) => Temporal.Duration.from({minutes: m});
        export default() => {
          return Spans.ForEachActivity(ActivityType.activity, i => i.span()).selectWhenTrue(Windows.Value(true)).windows()
        }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(new SpansSelectWhenTrue(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")
                ),
                new WindowsValue(true)
            ))
        )
    );
  }
  @Test
  void testSpansConnectTo() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Windows.Value(true).spans().connectTo(
              Windows.Value(false).spans()
            ).windows();
          }
        """,
        new ViolationsOfWindows(
            new WindowsFromSpans(
                new SpansConnectTo(
                    new SpansFromWindows(new WindowsValue(true)),
                    new SpansFromWindows(new WindowsValue(false))
                )
            )
        )
    );
  }

  @Test
  void testSpansContains() {
    checkSuccessfulCompilation(
        """
          export default () => {
            return Spans.ForEachActivity(ActivityType.activity).contains(Spans.ForEachActivity(ActivityType.activity));
          }
        """,
        new ViolationsOfWindows(
            new SpansContains(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")
                ),
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 1",
                    new ActivitySpan("span activity alias 1")
                ),
                SpansContains.Requirement.newDefault()
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Spans.ForEachActivity(ActivityType.activity).contains(Spans.ForEachActivity(ActivityType.activity), {count: 3});
          }
        """,
        new ViolationsOfWindows(
            new SpansContains(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")
                ),
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 1",
                    new ActivitySpan("span activity alias 1")
                ),
                new SpansContains.Requirement(
                    Optional.of(3),
                    Optional.of(3),
                    Optional.empty(),
                    Optional.empty()
                )
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Spans.ForEachActivity(ActivityType.activity).contains(Spans.ForEachActivity(ActivityType.activity), {duration: {min: Temporal.Duration.from({minutes: 1})}});
          }
        """,
        new ViolationsOfWindows(
            new SpansContains(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")
                ),
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 1",
                    new ActivitySpan("span activity alias 1")
                ),
                new SpansContains.Requirement(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new DurationLiteral(Duration.of(1, Duration.MINUTE))),
                    Optional.empty()
                )
            )
        )
    );

    checkSuccessfulCompilation(
        """
          export default () => {
            return Spans.ForEachActivity(ActivityType.activity).contains(Spans.ForEachActivity(ActivityType.activity), {
              count: {min: 1, max: 2},
              duration: {min: Temporal.Duration.from({minutes: 1}), max: Temporal.Duration.from({minutes: 2})}
            });
          }
        """,
        new ViolationsOfWindows(
            new SpansContains(
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 0",
                    new ActivitySpan("span activity alias 0")
                ),
                new ForEachActivitySpans(
                    "activity",
                    "span activity alias 1",
                    new ActivitySpan("span activity alias 1")
                ),
                new SpansContains.Requirement(
                    Optional.of(1),
                    Optional.of(2),
                    Optional.of(new DurationLiteral(Duration.of(1, Duration.MINUTE))),
                    Optional.of(new DurationLiteral(Duration.of(2, Duration.MINUTE)))
                )
            )
        )
    );
  }
}
