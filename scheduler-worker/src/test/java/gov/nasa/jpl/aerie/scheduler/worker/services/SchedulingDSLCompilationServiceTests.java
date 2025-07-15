package gov.nasa.jpl.aerie.scheduler.worker.services;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.constraints.tree.ActivitySpan;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteProfileFromDuration;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteResource;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteValue;
import gov.nasa.jpl.aerie.constraints.tree.DurationLiteral;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivitySpans;
import gov.nasa.jpl.aerie.constraints.tree.GreaterThan;
import gov.nasa.jpl.aerie.constraints.tree.IntervalAlias;
import gov.nasa.jpl.aerie.constraints.tree.IntervalDuration;
import gov.nasa.jpl.aerie.constraints.tree.LessThan;
import gov.nasa.jpl.aerie.constraints.tree.ListExpressionAt;
import gov.nasa.jpl.aerie.constraints.tree.LongerThan;
import gov.nasa.jpl.aerie.constraints.tree.Not;
import gov.nasa.jpl.aerie.constraints.tree.Or;
import gov.nasa.jpl.aerie.constraints.tree.ProfileExpression;
import gov.nasa.jpl.aerie.constraints.tree.RealParameter;
import gov.nasa.jpl.aerie.constraints.tree.RealResource;
import gov.nasa.jpl.aerie.constraints.tree.RealValue;
import gov.nasa.jpl.aerie.constraints.tree.Starts;
import gov.nasa.jpl.aerie.constraints.tree.StructExpressionAt;
import gov.nasa.jpl.aerie.constraints.tree.ValueAt;
import gov.nasa.jpl.aerie.constraints.tree.WindowsFromSpans;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.TimeUtility;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeAnchor;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.model.PersistentTimeAnchor;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.models.ExternalProfiles;
import gov.nasa.jpl.aerie.scheduler.server.models.MerlinPlan;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanMetadata;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingDSL;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinDatabaseService;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinServiceException;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.scheduler.server.services.TypescriptCodeGenerationServiceTestFixtures.MISSION_MODEL_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulingDSLCompilationServiceTests {
  private static final PlanId PLAN_ID = new PlanId(1L);
  private static final MerlinDatabaseService.ReaderRole merlinDatabaseService = new MerlinDatabaseService.ReaderRole() {
    @Override
    public MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final PlanId missionModelId)
    {
      return MISSION_MODEL_TYPES;
    }

    @Override
    public MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final MissionModelId missionModelId)
    {
      return MISSION_MODEL_TYPES;
    }

    @Override
    public long getPlanRevision(final PlanId planId) {
      return 0;
    }

    @Override
    public PlanMetadata getPlanMetadata(final PlanId planId) {
      return null;
    }

    @Override
    public MerlinPlan getPlanActivityDirectives(final PlanMetadata planMetadata, final Problem mission) {
      return null;
    }

    @Override
    public void ensurePlanExists(final PlanId planId) {

    }

    @Override
    public Optional<Pair<SimulationResults, DatasetId>> getSimulationResults(final PlanMetadata planMetadata) {
      return Optional.empty();
    }

    @Override
    public ExternalProfiles getExternalProfiles(final PlanId planId) {
      return null;
    }

    @Override
    public Map<String, List<ExternalEvent>> getExternalEvents(final PlanId planId, final Instant horizonStart)
    throws MerlinServiceException, IOException
    {
      return Map.of();
    }

    @Override
    public Collection<ResourceType> getResourceTypes(final PlanId planId) {
      return null;
    }

    @Override
    public Map<ActivityDirectiveId, GoalId> getActivityIdToGoalIdMap(final PlanId planId)
    throws MerlinServiceException, IOException
    {
      return Map.of();
    }
  };
  SchedulingDSLCompilationService schedulingDSLCompilationService;

  @BeforeAll
  void setUp() throws IOException {
    schedulingDSLCompilationService = new SchedulingDSLCompilationService();
  }

  @AfterAll
  void tearDown() {
    schedulingDSLCompilationService.close();
  }

  @Test
  void  testSchedulingDSL_mutex()
  {
    final var result = schedulingDSLCompilationService.compileGlobalSchedulingCondition(
        merlinDatabaseService,
        PLAN_ID, """
                  export default function myCondition() {
                    return GlobalSchedulingCondition.mutex([ActivityTypes.SampleActivity2], [ActivityTypes.SampleActivity1])
                  }
              """,
        List.of());
    final var expectedGoalDefinition = new SchedulingDSL.ConditionSpecifier.AndCondition(List.of(
        new SchedulingDSL.ConditionSpecifier.GlobalSchedulingCondition(
            new Not(
                new Or(
                    new WindowsFromSpans(
                        new ForEachActivitySpans(
                            "SampleActivity1",
                            "span activity alias 0",
                            new ActivitySpan("span activity alias 0"))
                    )
                )
            ),
            List.of("SampleActivity2")
        ),
        new SchedulingDSL.ConditionSpecifier.GlobalSchedulingCondition(
            new Not(
                new Or(
                    new WindowsFromSpans(
                        new ForEachActivitySpans(
                            "SampleActivity2",
                            "span activity alias 1",
                            new ActivitySpan("span activity alias 1"))
                    )
                )
            ),
            List.of("SampleActivity1")
        )
    ));

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.ConditionSpecifier> r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.ConditionSpecifier> r) {
      fail(r.toString());
    }
  }

  private static StructExpressionAt getSampleActivity1Parameters() {
    return new StructExpressionAt(Map.ofEntries(
        Map.entry("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option2")))),
        Map.entry("fancy", new ProfileExpression<>(new StructExpressionAt(Map.ofEntries(
                      Map.entry("subfield1", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("value1")))),
                      Map.entry("subfield2", new ProfileExpression<>(new ListExpressionAt(List.of(new ProfileExpression<>(new StructExpressionAt(Map.of("subsubfield1",
                                                                                                        new ProfileExpression<>(new DiscreteValue(SerializedValue.of(2 )))))))))))
                  ))
        ),
        Map.entry("duration", new ProfileExpression<>(new DiscreteProfileFromDuration(new DurationLiteral(Duration.of(1, HOUR)))))
    ));
  }

  private static StructExpressionAt getSampleActivity1ParametersWithDurationReference() {
    return new StructExpressionAt(Map.ofEntries(
        Map.entry("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option2")))),
        Map.entry("fancy", new ProfileExpression<>(new StructExpressionAt(Map.ofEntries(
                      Map.entry("subfield1", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("value1")))),
                      Map.entry("subfield2", new ProfileExpression<>(new ListExpressionAt(List.of(new ProfileExpression<>(new StructExpressionAt(Map.of("subsubfield1",
                                                                                                                                                        new ProfileExpression<>(new DiscreteValue(SerializedValue.of(2 )))))))))))
                  ))
        ),
        Map.entry("duration", new ProfileExpression<>(new DiscreteProfileFromDuration(new IntervalDuration(new IntervalAlias("coexistence interval alias 0")))))
    ));
  }

  private static StructExpressionAt getSampleActivity2PresetParameters() {
    return new StructExpressionAt(Map.of(
        "quantity", new ProfileExpression<>(new DiscreteValue(SerializedValue.of(5)))
    ));
  }

  private static StructExpressionAt getSampleActivity3PresetParameters() {
    return new StructExpressionAt(Map.ofEntries(
       Map.entry("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option1"))))
    ));
  }

  @Test
  void  testSchedulingDSL_basic()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity1({
                      variant: 'option2',
                      fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                      duration: Temporal.Duration.from({ hours: 1 })
                    }),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity1",
            getSampleActivity1Parameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_recurrence_activityFinder()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityFinder: ActivityExpression.build(ActivityTypes.SampleActivity1, {variant:'option2'}),
                    activityTemplate: ActivityTemplates.SampleActivity1({
                      variant: 'option2',
                      fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                      duration: Temporal.Duration.from({ hours: 1 })
                    }),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity1",
            getSampleActivity1Parameters()
        ),
        Optional.of(new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity1",Optional.of(
            new StructExpressionAt(Map.of("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option2")))))
        ))),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void  testSchedulingDSL_partial()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity1({
                      variant: 'option2',
                      fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                      duration: Temporal.Duration.from({ hours: 1 })
                    }),
                    interval: Temporal.Duration.from({ hours: 1 })
                  }).shouldRollbackIfUnsatisfied(true)
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity1",
            getSampleActivity1Parameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        true);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_helper_function()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return myHelper(ActivityTemplates.SampleActivity1({
                    variant: 'option2',
                    fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                    duration: Temporal.Duration.from({ hours: 1 })
                  }))
                }
                function myHelper(activityTemplate) {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate,
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity1",
            getSampleActivity1Parameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_variable_not_defined() {
    final SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> actualErrors;
    actualErrors = (SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier>) schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
          PLAN_ID, """
                export default function myGoal() {
                  const x = 4 - 2
                  return myHelper(ActivityTemplates.SampleActivity1({
                    variant: 'option2',
                    fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                    duration: Temporal.Duration.from({ hours: 1 })
                  }))
                }
                function myHelper(activityTemplate) {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate,
                    interval: x
                  })
                }
              """);
    assertTrue(
        actualErrors.errors()
                    .stream()
                    .anyMatch(e -> e.message().contains("TypeError: TS2304 Cannot find name 'x'."))
    );
  }

  @Test
  void testSchedulingDSL_applyWhen()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
        export default function myGoal() {
          return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity1({
                        variant: 'option2',
                        fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                        duration: Temporal.Duration.from({ hours : 1 })
                    }),
                    interval: Temporal.Duration.from({ hours : 1 })
                  }).applyWhen(Real.Resource(Resources["/sample/resource/1"]).greaterThan(2.0))
        }
        """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.GoalApplyWhen(
        new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
            new SchedulingDSL.ActivityTemplate(
                "SampleActivity1",
                getSampleActivity1Parameters()
            ),
            Optional.empty(),
            HOUR,
            HOUR,
            Optional.empty(),
            false
        ),
        new GreaterThan(
            new RealResource("/sample/resource/1"),
            new RealValue(2.0)
        )
    );
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_wrong_return_type() {
    final SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> actualErrors;
    actualErrors = (SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier>) schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
          PLAN_ID, """
                export default function myGoal() {
                  return 5
                }
              """);
    assertTrue(
        actualErrors.errors()
                    .stream()
                    .anyMatch(e -> e.message().contains("TypeError: TS2322 Incorrect return type. Expected: 'Goal | Promise<Goal>', Actual: 'number'."))
    );
  }

  @Test
  void testSchedulingDSL_temporal() {
    final SchedulingDSLCompilationService.SchedulingDSLCompilationResult<?> result;
    result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID,
        """
                    export default () => Goal.ActivityRecurrenceGoal({
                      activityTemplate: ActivityTemplates.SampleActivity1({
                        variant: 'option2',
                        fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                        duration: Temporal.Duration.from({ hours: 1 })
                      }),
                      interval:  Temporal.Duration.from({days: 1})
                    })
                    """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity1",
            getSampleActivity1Parameters()
        ),
        Optional.empty(),
        Duration.HOURS.times(24),
        Duration.HOURS.times(24),
        Optional.empty(),
        false
    );
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error r) {
      fail(r.toString());
    }
  }


  @Test
  void testHugeGoal() {
    // This test is intended to create a Goal that is bigger than the node subprocess's standard input buffer
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity1({
                      variant: 'option2',
                      fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                      duration: Temporal.Duration.from({ hours: 1 })
                    }),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """ + " ".repeat(9001));
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity1",
            getSampleActivity1Parameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false
    );
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testCoexistenceGoalActivityExpression() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
          export default function() {
            return Goal.CoexistenceGoal({
              persistentAnchor: PersistentTimeAnchor.START,
              activityTemplate: (span) => ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                duration: Temporal.Duration.from({ hours : 1 })
              }),
              forEach: ActivityExpression.ofType(ActivityTypes.SampleActivity2),
              startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ seconds : 1 }))
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                                                 getSampleActivity1Parameters()
              ),
                  Optional.of(PersistentTimeAnchor.START),
              Optional.empty(),
              "coexistence activity alias 0",
              new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity2", Optional.empty()),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.START, TimeUtility.Operator.PLUS, SECOND, true)),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testCoexistenceGoalFlexibleTimingConstraint() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
          export default function() {
            return Goal.CoexistenceGoal({
              activityTemplate: (span) => ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                duration: Temporal.Duration.from({ hours : 1 })
              }),
              forEach: ActivityExpression.ofType(ActivityTypes.SampleActivity2),
              startsWithin: TimingConstraint.bounds(
                TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ seconds : 1 })),
                TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ seconds : 5 })))
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                                                 getSampleActivity1Parameters()
              ),
                  Optional.of(PersistentTimeAnchor.DISABLED),
              Optional.empty(),
              "coexistence activity alias 0",
              new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity2", Optional.empty()),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraintFlexibleRange(
                  new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.START, TimeUtility.Operator.PLUS, SECOND, true),
                  new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.START, TimeUtility.Operator.PLUS, Duration.of(5, SECONDS), true),
                  false
              )),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testCoexistenceGoalActivityFinder() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
          export default function() {
            return Goal.CoexistenceGoal({
              activityFinder: ActivityExpression.build(ActivityTypes.SampleActivity1, {variant:'option2'}),
              persistentAnchor: PersistentTimeAnchor.START,
              activityTemplate: (span) => ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                duration: Temporal.Duration.from({ hours : 1 })
              }),
              forEach: ActivityExpression.ofType(ActivityTypes.SampleActivity2),
              startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ seconds : 1 }))
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                                                 getSampleActivity1Parameters()
              ),
                  Optional.of(PersistentTimeAnchor.START),
              Optional.of(new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity1", Optional.of(
                  new StructExpressionAt(Map.of("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option2")))))
              ))),
              "coexistence activity alias 0",
              new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity2", Optional.empty()),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.START, TimeUtility.Operator.PLUS, SECOND, true)),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testCoexistenceGoalParameterReference() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
          export default function() {
            return Goal.CoexistenceGoal({
              activityTemplate: (anchorActivity) => ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: anchorActivity.parameters.quantity}]},
                duration: Temporal.Duration.from({ hours : 1 })
              }),
              forEach: ActivityExpression.ofType(ActivityTypes.SampleActivity2),
              startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ seconds : 1 }))
            })
          }
    """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                   new StructExpressionAt(Map.ofEntries(
                      Map.entry("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option2")))),
                      Map.entry("fancy", new ProfileExpression<>(new StructExpressionAt(Map.ofEntries(
                                    Map.entry("subfield1", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("value1")))),
                                    Map.entry("subfield2", new ProfileExpression<>(new ListExpressionAt(List.of(new ProfileExpression<>(new StructExpressionAt(Map.of("subsubfield1",
                                                                                                                                                                      new ProfileExpression<>(new RealParameter("coexistence activity alias 0", "quantity"))))))))))
                                ))
                      ),
                      Map.entry("duration", new ProfileExpression<>(new DiscreteProfileFromDuration(new DurationLiteral(Duration.of(1, HOUR)))))
                  ))
              ),
              Optional.of(PersistentTimeAnchor.DISABLED),
              Optional.empty(),
              "coexistence activity alias 0",
              new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity2", Optional.empty()),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.START, TimeUtility.Operator.PLUS, SECOND, true)),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testCoexistenceGoalParameterReferenceValueAt() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
          export default function() {
            return Goal.CoexistenceGoal({
              activityTemplate: (anchorActivity) => ActivityTemplates.SampleActivity1({
                variant: Discrete.Resource('/sample/resource/3').valueAt(anchorActivity.span().starts()),
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2.0}]},
                duration: Temporal.Duration.from({ hours : 1 })
              }),
              forEach: ActivityExpression.ofType(ActivityTypes.SampleActivity2),
              startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ seconds : 1 }))
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                                                 new StructExpressionAt(Map.ofEntries(
                                                     Map.entry("variant", new ProfileExpression<>(new ValueAt<>(new ProfileExpression<>(new DiscreteResource("/sample/resource/3")),new Starts<>(new ActivitySpan("coexistence activity alias 0"))))),
                                                     Map.entry("fancy", new ProfileExpression<>(new StructExpressionAt(Map.ofEntries(
                                                                   Map.entry("subfield1", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("value1")))),
                                                                   Map.entry("subfield2", new ProfileExpression<>(new ListExpressionAt(List.of(new ProfileExpression<>(new StructExpressionAt(Map.of("subsubfield1",
                                                                                                                                                                                                     new ProfileExpression<>(new DiscreteValue(SerializedValue.of(2)))))))))))
                                                               ))
                                                     ),
                                                     Map.entry("duration", new ProfileExpression<>(new DiscreteProfileFromDuration(new DurationLiteral(Duration.of(1, HOUR)))))
                                                 ))
              ),
                  Optional.of(PersistentTimeAnchor.DISABLED),
              Optional.empty(),
              "coexistence activity alias 0",
              new SchedulingDSL.ConstraintExpression.ActivityExpression("SampleActivity2", Optional.empty()),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.START, TimeUtility.Operator.PLUS, SECOND, true)),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void strictTypeCheckingTest_astNode() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID,
        """
          interface FakeGoal {
            and(...others: FakeGoal[]): FakeGoal;
            or(...others: FakeGoal[]): FakeGoal;
            applyWhen(window: Windows): FakeGoal;
          }
          export default function() {
            const myFakeGoal: FakeGoal = {
              and: (...others: FakeGoal[]) => {
                return myFakeGoal;
              },
              or: (...others: FakeGoal[]) => {
                return myFakeGoal;
              },
              applyWhen: (window: Windows) => {
                return myFakeGoal;
              },
            };
            return myFakeGoal;
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(1, r.errors().size());
      assertTrue(r.errors().get(0).message().contains("Incorrect return type. Expected: 'Goal | Promise<Goal>', Actual: 'FakeGoal'."));
    }
  }

  @Test
  void strictTypeCheckingTest_transition() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID,
        """
          export default function() {
            return Goal.CoexistenceGoal({
              activityTemplate: ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                duration: Temporal.Duration.from({ hours: 1 })
              }),
              forEach: Discrete.Resource(Resources["/sample/resource/1"]).transition("Chiquita", "Dole"),
              startsAt: TimingConstraint.singleton(WindowProperty.END)
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(1, r.errors().size());
      assertEquals(
          "TypeError: TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
          r.errors().get(0).message()
      );
    }
  }

  @Test
  void testSchedulingDSL_emptyActivityCorrect()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                    interval: Temporal.Duration.from({ hours : 1 }) // 1 hour in microseconds
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivityEmpty",
            new StructExpressionAt(Map.of())
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false
    );
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_recurrenceGoalParameters()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                    separatedByAtMost: Temporal.Duration.from({ hours : 1 }), // 1 hour in microseconds
                    separatedByAtLeast: Temporal.Duration.from({ minutes : 30 })
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivityEmpty",
            new StructExpressionAt(Map.of())
        ),
        Optional.empty(),
        HOUR,
        MINUTE.times(30),
        Optional.empty(),
        false
    );
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_recurrenceGoalParametersPreviousAct()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                    separatedByAtMost: Temporal.Duration.from({ hours : 1 }), // 1 hour in microseconds
                    separatedByAtLeast: Temporal.Duration.from({ minutes : 30 }),
                    previousActivityStartedAt: Temporal.Duration.from({ minutes: -40 })
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivityEmpty",
            new StructExpressionAt(Map.of())
        ),
        Optional.empty(),
        HOUR,
        MINUTE.times(30),
        Optional.of(Duration.negate(MINUTE.times(40))),
        false
    );
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSL_emptyActivityBogus()
  {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivityEmpty({ fake: "bogus" }),
                    interval: Temporal.Duration.from({ hours : 1 }) // 1 hour in microseconds
                  })
                }
            """);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(1, r.errors().size());
      assertEquals(
          "TypeError: TS2554 Expected 0 arguments, but got 1.",
          r.errors().get(0).message()
      );
    }
    else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      fail(r.value().toString());
    }
  }

  @Test
  void testCoexistenceGoalStateConstraint() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID,
        """
          const micro = (m: number) => Temporal.Duration.from({microseconds: m});
          export default function() {
            return Goal.CoexistenceGoal({
              activityTemplate: ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                duration: Temporal.Duration.from({ hours: 1 })
              }),
              forEach: Real.Resource(Resources["/sample/resource/1"]).greaterThan(50.0).longerThan(micro(10)),
              startsAt: TimingConstraint.singleton(WindowProperty.END)
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<?> r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                                                 getSampleActivity1Parameters()
              ),
                  Optional.of(PersistentTimeAnchor.DISABLED),
              Optional.empty(),
              "coexistence interval alias 0",
              new SchedulingDSL.ConstraintExpression.WindowsExpression(new LongerThan(new GreaterThan(new RealResource("/sample/resource/1"), new RealValue(50.0)), new DurationLiteral(Duration.of(10, Duration.MICROSECOND)))),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.END, TimeUtility.Operator.PLUS, Duration.ZERO, true)),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testCoexistenceGoalReferenceWindowDuration() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID,
        """
          const micro = (m: number) => Temporal.Duration.from({microseconds: m});
          export default function() {
            return Goal.CoexistenceGoal({
              activityTemplate: (i: Interval) => ActivityTemplates.SampleActivity1({
                variant: 'option2',
                fancy: { subfield1: 'value1', subfield2: [{subsubfield1: 2}]},
                duration: i.duration()
              }),
              forEach: Real.Resource(Resources["/sample/resource/1"]).greaterThan(50.0).longerThan(micro(10)),
              startsAt: TimingConstraint.singleton(WindowProperty.END)
            })
          }
        """);

    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<?> r) {
      assertEquals(
          new SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition(
              new SchedulingDSL.ActivityTemplate("SampleActivity1",
                                                 getSampleActivity1ParametersWithDurationReference()
              ),
                  Optional.of(PersistentTimeAnchor.DISABLED),
              Optional.empty(),
              "coexistence interval alias 0",
              new SchedulingDSL.ConstraintExpression.WindowsExpression(new LongerThan(new GreaterThan(new RealResource("/sample/resource/1"), new RealValue(50.0)), new DurationLiteral(Duration.of(10, Duration.MICROSECOND)))),
              Optional.of(new SchedulingDSL.TimingConstraint.ActivityTimingConstraint(TimeAnchor.END, TimeUtility.Operator.PLUS, Duration.ZERO, true)),
              Optional.empty(),
              false
          ),
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testWindowsExpression() {
    final var result = schedulingDSLCompilationService.compileGlobalSchedulingCondition(
        merlinDatabaseService,
        PLAN_ID,
        """
          export default function() {
            return GlobalSchedulingCondition.scheduleOnlyWhen([], Real.Resource("/sample/resource/1").lessThan(5.0));
          }
        """,
        List.of());
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          new SchedulingDSL.ConditionSpecifier.GlobalSchedulingCondition(
              new LessThan(
                  new RealResource("/sample/resource/1"),
                  new RealValue(5.0)),
              List.of()),
          r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error r) {
      fail(r.toString());
    }
  }

  @Test
  void testAndGoal(){
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                    interval: Temporal.Duration.from({ hours : 1 }) // 1 hour in microseconds
                  }).and(
                    Goal.ActivityRecurrenceGoal({
                      activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                      interval: Temporal.Duration.from({ hours : 2 }) // 2 hour in microseconds
                    })
                  )
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.GoalAnd(List.of(
        new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
          new SchedulingDSL.ActivityTemplate(
              "SampleActivityEmpty",
              new StructExpressionAt(Map.of())
          ),
          Optional.empty(),
          HOUR,
          HOUR,
          Optional.empty(),
          false
    ), new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
            new SchedulingDSL.ActivityTemplate(
                "SampleActivityEmpty",
                new StructExpressionAt(Map.of())
            ),
            Optional.empty(),
            HOUR.times(2),
            HOUR.times(2),
            Optional.empty(),
            false
        )),false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success r) {
      assertEquals(
          expectedGoalDefinition,
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error r) {
      fail(r.toString());
    }
  }

  @Test
  void testOrGoal(){
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default function myGoal() {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                    interval: Temporal.Duration.from({ hours: 1 }) // 1 hour in microseconds
                  }).or(
                    Goal.ActivityRecurrenceGoal({
                      activityTemplate: ActivityTemplates.SampleActivityEmpty(),
                      interval: Temporal.Duration.from({ hours : 2 }) // 2 hour in microseconds
                    })
                  )
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.GoalOr(List.of(
        new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
            new SchedulingDSL.ActivityTemplate(
                "SampleActivityEmpty",
                new StructExpressionAt(Map.of())
            ),
            Optional.empty(),
            HOUR,
            HOUR,
            Optional.empty(),
            false
        ), new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
            new SchedulingDSL.ActivityTemplate(
                "SampleActivityEmpty",
                new StructExpressionAt(Map.of())
            ),
            Optional.empty(),
            HOUR.times(2),
            HOUR.times(2),
            Optional.empty(),
            false
        )),false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(
          expectedGoalDefinition,
          r.value()
      );
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error r) {
      fail(r.toString());
    }
  }

  @Test
  void  testActivityPreset() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default (): Goal => {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity2(ActivityPresets.SampleActivity2["my preset"]),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity2",
            getSampleActivity2PresetParameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testSchedulingDSLMutatingPreset() {

    final var result1 = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default (): Goal => {
                  let preset = ActivityPresets.SampleActivity2["my preset"];
                  preset.quantity = 9;
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity2(ActivityPresets.SampleActivity2["my preset"]),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition1 = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity2",
            getSampleActivity2PresetParameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result1 instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition1, r.value());
    } else if (result1 instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }

    final var result2 = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default (): Goal => {
                  let preset = {
                    ...ActivityPresets.SampleActivity2["my preset"],
                    quantity: 9
                  };
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity2(preset),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition2 = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity2",
            new StructExpressionAt(Map.of(
                "quantity", new ProfileExpression<>(new DiscreteValue(SerializedValue.of(9)))
            ))
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result2 instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition2, r.value());
    } else if (result2 instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

  @Test
  void testPresetWithEnum() {
    final var result = schedulingDSLCompilationService.compileSchedulingGoalDSL(
        merlinDatabaseService,
        PLAN_ID, """
                export default (): Goal => {
                  return Goal.ActivityRecurrenceGoal({
                    activityTemplate: ActivityTemplates.SampleActivity3(ActivityPresets.SampleActivity3["my preset"]),
                    interval: Temporal.Duration.from({ hours: 1 })
                  })
                }
            """);
    final var expectedGoalDefinition1 = new SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition(
        new SchedulingDSL.ActivityTemplate(
            "SampleActivity3",
            getSampleActivity3PresetParameters()
        ),
        Optional.empty(),
        HOUR,
        HOUR,
        Optional.empty(),
        false);
    if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Success<SchedulingDSL.GoalSpecifier> r) {
      assertEquals(expectedGoalDefinition1, r.value());
    } else if (result instanceof SchedulingDSLCompilationService.SchedulingDSLCompilationResult.Error<SchedulingDSL.GoalSpecifier> r) {
      fail(r.toString());
    }
  }

}
