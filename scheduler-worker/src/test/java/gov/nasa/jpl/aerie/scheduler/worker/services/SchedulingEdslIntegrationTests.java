package gov.nasa.jpl.aerie.scheduler.worker.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOURS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.driver.ActivityDirective;
import gov.nasa.jpl.aerie.merlin.driver.ActivityDirectiveId;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.driver.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.TimeUtility;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.server.config.PlanOutputMode;
import gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers;
import gov.nasa.jpl.aerie.scheduler.server.models.ExternalProfiles;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalType;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingConditionId;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingConditionRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingConditionSource;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalSource;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import gov.nasa.jpl.aerie.scheduler.server.models.Specification;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.models.Timestamp;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.SpecificationRevisionData;
import gov.nasa.jpl.aerie.scheduler.server.services.MerlinDatabaseService;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleRequest;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.server.services.SpecificationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SchedulingEdslIntegrationTests {

  public static final PlanningHorizon PLANNING_HORIZON = new PlanningHorizon(
      TimeUtility.fromDOY("2021-001T00:00:00"),
      TimeUtility.fromDOY("2021-005T00:00:00"));

  private record MissionModelDescription(String name, Map<String, SerializedValue> config, Path libPath) {}

  private record EdslGoal(GoalId goalId, String definition, boolean enabled, boolean simulateAfter) {
    public EdslGoal(GoalId goalId, String definition, boolean enabled) {
      this(goalId, definition, enabled, true);
    }
  }

  private static final MissionModelDescription BANANANATION = new MissionModelDescription(
      "bananantion",
      Map.of("initialDataPath", SerializedValue.of("/etc/hosts")),
      Path.of(System.getenv("AERIE_ROOT"), "examples", "banananation", "build", "libs")
  );
  private static final MissionModelDescription MINIMAL_MISSION_MODEL = new MissionModelDescription(
      "minimal-mission-model",
      Map.of(),
      Path.of(System.getenv("AERIE_ROOT"), "examples", "minimal-mission-model", "build", "libs")
  );

  private SchedulingDSLCompilationService schedulingDSLCompiler;

  @BeforeAll
  void setup() throws IOException {
    this.schedulingDSLCompiler = new SchedulingDSLCompilationService();
  }

  @Test
  void testEmptyPlanEmptySpecification() {
    final var results = runScheduler(BANANANATION, List.of(), List.of(), PLANNING_HORIZON);
    assertEquals(Map.of(), results.scheduleResults.goalResults());
  }

  @Test
  void testEmptyPlanSimpleRecurrenceGoal() {
    final var results = runScheduler(BANANANATION, List.of(), List.of(new EdslGoal(new GoalId(0L, 0L), """
        export default () => Goal.ActivityRecurrenceGoal({
          activityTemplate: ActivityTemplates.PeelBanana({
            peelDirection: "fromStem",
          }),
          interval: Temporal.Duration.from({ milliseconds: 24 * 60 * 60 * 1000 })
        })
        """, true)), PLANNING_HORIZON);
    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));
    assertTrue(goalResult.satisfied());
    assertEquals(4, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }
    final var updatedPlan = results.updatedPlan();
    for (final var activity : updatedPlan) {
      final var arguments = activity.serializedActivity().getArguments();
      assertEquals("PeelBanana", activity.serializedActivity().getTypeName());
      assertEquals(SerializedValue.of("fromStem"), arguments.get("peelDirection"));
    }
  }

  @Test
  void testRecurrenceGoalNegative() {
    try {
      runScheduler(BANANANATION, List.of(), List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({
              peelDirection: "fromStem",
            }),
            interval: Temporal.Duration.from({ hours : -4})
          })
          """, true)), PLANNING_HORIZON);
      fail();
    }
    catch (AssertionError e) {
      assertTrue(e.getMessage().contains("Duration passed to RecurrenceGoal as the goal's minimum recurrence interval cannot be negative!"));
    }
    catch (Exception e) {
      fail(e.getMessage());
    }
  }

  @Test
  void testEmptyPlanDurationCardinalityGoal() {
    final var results = runScheduler(BANANANATION, List.of(), List.of(new EdslGoal(new GoalId(0L, 0L), """
        export default function myGoal() {
                          return Goal.CardinalityGoal({
                            activityTemplate: ActivityTemplates.GrowBanana({
                              quantity: 1,
                              growingDuration: Temporal.Duration.from({seconds: 1}),
                            }),
                            specification : {duration: Temporal.Duration.from({seconds: 10})}
                          })
        }
          """, true)),List.of(createAutoMutex("GrowBanana")), PLANNING_HORIZON);
    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(10, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var activitiesByType = partitionByActivityType(results.updatedPlan());

    final var growBananas = activitiesByType.get("GrowBanana");
    assertEquals(10, growBananas.size());

    final var setStartTimes = new HashSet<>(Stream
                                              .of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
                                              .map(x -> Duration.of(x, Duration.SECOND)).toList());
    for (final var growBanana : growBananas) {
      assertTrue(setStartTimes.remove(growBanana.startOffset()));
      assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));
    }
  }

  @Test
  void testEmptyPlanOccurrenceCardinalityGoal() {
    final var results = runScheduler(BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                  export default function myGoal() {
                                    return Goal.CardinalityGoal({
                                      activityTemplate: ActivityTemplates.GrowBanana({
                                        quantity: 1,
                                        growingDuration: Temporal.Duration.from({seconds: 1}),
                                      }),
                                      specification : {occurrence: 10}
                                    })
                  }
                    """, true)),List.of(createAutoMutex("GrowBanana")),PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(10, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var activitiesByType = partitionByActivityType(results.updatedPlan());

    final var growBananas = activitiesByType.get("GrowBanana");
    assertEquals(10, growBananas.size());

    final var setStartTimes = new HashSet<>(Stream
                                              .of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
                                              .map(x -> Duration.of(x, Duration.SECOND)).toList());
    for (final var growBanana : growBananas) {
      assertTrue(setStartTimes.remove(growBanana.startOffset()));
      assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));
    }
  }

  @Test
  void testEmptyPlanOccurrenceUnitaryGoalTimeInstant() {
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: Temporal.Instant.from("2021-01-01T05:00:00.000Z"),
            activityTemplate: (span) => ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var activitiesByType = partitionByActivityType(results.updatedPlan());

    final var peelBananas = activitiesByType.get("PeelBanana");
    assertEquals(1, peelBananas.size());

    final var peelBanana = peelBananas.iterator().next();
    assertEquals(Duration.of(5, Duration.HOUR), peelBanana.startOffset());
  }

  @Test
  void testEmptyPlanOccurrenceUnitaryGoalTimeInterval() {
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: Interval.Between(Temporal.Instant.from("2021-01-01T05:00:00.000Z"), Temporal.Instant.from("2021-01-01T10:00:00.000Z"), Inclusivity.Inclusive, Inclusivity.Exclusive),
            activityTemplate: (span) => ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var activitiesByType = partitionByActivityType(results.updatedPlan());

    final var peelBananas = activitiesByType.get("PeelBanana");
    assertEquals(1, peelBananas.size());

    final var peelBanana = peelBananas.iterator().next();
    assertEquals(Duration.of(5, Duration.HOUR), peelBanana.startOffset());
  }

  @Test
  void testSingleActivityPlanSimpleRecurrenceGoal() {
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
            export default () => Goal.ActivityRecurrenceGoal({
              activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
              interval: Temporal.Duration.from({days: 1})
            })
            """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(4, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var activitiesByType = partitionByActivityType(results.updatedPlan());
    final var biteBananas = activitiesByType.get("BiteBanana");
    assertEquals(1, biteBananas.size());

    final var biteBanana = biteBananas.iterator().next();
    assertEquals(SerializedValue.of(1), biteBanana.serializedActivity().getArguments().get("biteSize"));

    final var peelBananas = activitiesByType.get("PeelBanana");
    assertEquals(4, peelBananas.size());

    for (final var peelBanana : peelBananas) {
      assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    }
  }
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoalWithValueAtParams() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(new ActivityDirective(
            Duration.ZERO,
            "GrowBanana",
            Map.of(
                "quantity", SerializedValue.of(3),
                "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
            null,
            true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (growBananaActivity) => ActivityTemplates.ChangeProducer({producer: Discrete.Resource("/producer").valueAt(growBananaActivity.span().starts())}),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var changeProducers = planByActivityType.get("ChangeProducer");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, changeProducers.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = changeProducers.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of("Chiquita"), peelBanana.serializedActivity().getArguments().get("producer"));

    assertEquals(growBanana.startOffset().plus(growBananaDuration).plus(Duration.of(5, Duration.MINUTES)), peelBanana.startOffset());
  }

  @Test
  void testCoexistencePartialAct() {
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true
            ),
            new ActivityDirective(
                Duration.MINUTE.times(5),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(Duration.MINUTE.in(MICROSECONDS))
                ),
                null,
                true
            )
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.BiteBanana),
            activityFinder: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (interval) => ActivityTemplates.GrowBanana({quantity: 10, growingDuration: Temporal.Duration.from({minutes:1}) }),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(0, goalResult.createdActivities().size());
    assertEquals(1, goalResult.satisfyingActivities().size());
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }
  }

  /**
   * Here the anchor window is [01:00:00, plan end]. The coexisting activity starts at 00:55:00 and the `valueAt` interval for
   * the parametric parameter is then outside of the anchor window causing the intervals not to intersect as the valueAt
   * was evaluated only in the start ([00:55:00, 00:55:00]) interval. Now, it is evaluated in Interval.FOREVER. Determining
   * the right bounds for simulation is the scheduler's responsability (upstream of the goal conflict evaluation).
   */
  @Test
  void testBugValueAt() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(new ActivityDirective(
                    Duration.HOUR,
                    "GrowBanana",
                    Map.of(
                        "quantity", SerializedValue.of(3),
                        "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                    null,
                    true),
                new ActivityDirective(
                    Duration.MINUTE.times(50),
                    "ChangeProducer",
                    Map.of(
                        "producer", SerializedValue.of("Company")),
                    null,
                    true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: Real.Resource("/fruit").greaterThan(4.0),
            activityTemplate: interval => ActivityTemplates.ChangeProducer({producer: Discrete.Resource("/producer").valueAt(Spans.FromInterval(interval).starts())}),
            startsAt: TimingConstraint.singleton(WindowProperty.START).minus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    assertEquals(1, goalResult.satisfyingActivities().size());
    final var activityCreated = results.updatedPlan
        .stream()
        .filter(a -> a.startOffset().isEqualTo(Duration.MINUTES.times(55)))
        .collect(Collectors.toSet());
    assertEquals(1, activityCreated.size());
    assertEquals(new SerializedValue.StringValue("Company"), activityCreated.iterator().next().serializedActivity().getArguments().get("producer"));
  }

  @Test
  void testCoexistenceGoalWithAnchors() {
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true
            ),
            new ActivityDirective(
                Duration.MINUTE.times(5),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(Duration.MINUTE.in(Duration.MICROSECOND))
                ),
                null,
                true
            )
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            persistentAnchor: PersistentTimeAnchor.START,
            forEach: ActivityExpression.ofType(ActivityTypes.BiteBanana),
            activityFinder: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (interval) => ActivityTemplates.GrowBanana({quantity: 10, growingDuration: Temporal.Duration.from({minutes:1}) }),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(0, goalResult.createdActivities().size());
    assertEquals(1, goalResult.satisfyingActivities().size());
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }
  }

  @Test
  void testCoexistenceGoalWithAnchorsCreation() {
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true
            )
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            persistentAnchor: PersistentTimeAnchor.START,
            forEach: ActivityExpression.ofType(ActivityTypes.BiteBanana),
            activityFinder: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (interval) => ActivityTemplates.GrowBanana({quantity: 10, growingDuration: Temporal.Duration.from({minutes:1}) }),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    assertEquals(1, goalResult.satisfyingActivities().size());
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan);
    final var allBiteBanana = planByActivityType.get("BiteBanana");
    for(final var activity : planByActivityType.get("GrowBanana")){
      assertNotNull(activity.anchorId());
      final var reference = results.idToAct().get(activity.anchorId());
      assertTrue(allBiteBanana.contains(reference));
      allBiteBanana.remove(reference);
    }
  }

  @Test
  void testCoexistencePartialActWithParameter() {
    final var expectedSatisfactionAct = new ActivityDirective(
        Duration.MINUTE.times(5),
        "GrowBanana",
        Map.of(
            "quantity", SerializedValue.of(1),
            "growingDuration", SerializedValue.of(Duration.MINUTE.times(2).in(MICROSECONDS))
        ),
        null,
        true
    );
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true
            ),
            new ActivityDirective(
                Duration.MINUTE.times(5),
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true
            ),
            expectedSatisfactionAct,
            new ActivityDirective(
                Duration.MINUTE.times(10),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(2),
                    "growingDuration", SerializedValue.of(Duration.MINUTE.times(2).in(MICROSECONDS))
                ),
                null,
                true
            )
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.BiteBanana),
            activityFinder: ActivityExpression.build(ActivityTypes.GrowBanana, {quantity: 1}),
            activityTemplate: (interval) => ActivityTemplates.GrowBanana({quantity: 1, growingDuration: Temporal.Duration.from({minutes:1}) }),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    assertEquals(2, goalResult.satisfyingActivities().size());
    var foundSatisfactionAct = false;
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
      final var element = results.idToAct.get(new ActivityDirectiveId(activity.id()));
      if(element != null && element.equals(expectedSatisfactionAct)){
        foundSatisfactionAct = true;
      }
    }
    assertTrue(foundSatisfactionAct);
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(3, growBananas.size());
    final var planByTime = partitionByStartTime(results.updatedPlan());
    assertEquals(2, planByTime.get(MINUTES.times(10)).size());
    var lookingFor = false;
    final var expectedCreation = new SerializedActivity("GrowBanana",
                                            Map.of("quantity", SerializedValue.of(1),
                                                   "growingDuration", SerializedValue.of(MINUTES.in(MICROSECONDS))));
    for(final var actAtTime10: planByTime.get(MINUTES.times(10))){
      if(actAtTime10.serializedActivity().equals(expectedCreation)){
        lookingFor = true;
        break;
      }
    }
    assertTrue(lookingFor);
  }

  @Test
  void testRecurrenceWithActivityFinder() {
    final var expectedMatch1 =  new ActivityDirective(
        Duration.of(0, SECONDS),
        "GrowBanana",
        Map.of(
            "quantity", SerializedValue.of(2),
            "growingDuration", SerializedValue.of(Duration.of(1, SECONDS).in(Duration.MICROSECONDS))),
        null,
        true);
    final var expectedMatch2 = new ActivityDirective(
        Duration.of(1, Duration.HOUR.times(24)),
        "GrowBanana",
        Map.of(
            "quantity", SerializedValue.of(2),
            "growingDuration", SerializedValue.of(Duration.of(2, SECONDS).in(Duration.MICROSECONDS))),
        null,
        true);

    final var results = runScheduler(
        BANANANATION,
        List.of(
           expectedMatch1,
            expectedMatch2,
            new ActivityDirective(
                Duration.of(3, Duration.HOUR.times(24)),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(3),
                    "growingDuration", SerializedValue.of(Duration.of(3, SECONDS).in(Duration.MICROSECONDS))),
                null,
                true)
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
            export default () => Goal.ActivityRecurrenceGoal({
                activityTemplate: ActivityTemplates.GrowBanana({quantity: 2, growingDuration: Temporal.Duration.from({seconds:1})}),
                activityFinder: ActivityExpression.build(ActivityTypes.GrowBanana, {quantity: 2}),
                interval: Temporal.Duration.from({ hours : 24 })
              })""", true)
        ),
        PLANNING_HORIZON
    );

    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));
    var foundFirst = false;
    var foundSecond = false;
    for(final var satisfyingActivity: goalResult.satisfyingActivities()){
      final var element = results.idToAct.get(new ActivityDirectiveId(satisfyingActivity.id()));
      if(element != null && element.equals(expectedMatch1)){
        foundFirst = true;
      }
      if(element != null && element.equals(expectedMatch2)){
        foundSecond = true;
      }
    }
    assertTrue(foundFirst && foundSecond);
    assertEquals(2, goalResult.createdActivities().size());
    assertEquals(PLANNING_HORIZON.getEndAerie().dividedBy(24,HOURS), goalResult.satisfyingActivities().size());
    assertTrue(goalResult.satisfied());
  }

  @Test
  void testCardinalityGoalWithActivityFinder() {
    final var results = runScheduler(
        BANANANATION,
        List.of(new ActivityDirective(
            Duration.of(0, SECONDS),
            "GrowBanana",
            Map.of(
                "quantity", SerializedValue.of(2),
                "growingDuration", SerializedValue.of(Duration.of(5, SECONDS).in(Duration.MICROSECONDS))),
            null,
            true)),
        List.of(new EdslGoal(new GoalId(0L, 0L),
                             """
        export default function myGoal() {
                          return Goal.CardinalityGoal({
                            activityFinder: ActivityExpression.ofType(ActivityTypes.GrowBanana),
                            activityTemplate: ActivityTemplates.GrowBanana({
                              quantity: 1,
                              growingDuration: Temporal.Duration.from({seconds: 1}),
                            }),
                            specification : {duration: Temporal.Duration.from({seconds: 10})}
                          })
        }
          """, true)),List.of(createAutoMutex("GrowBanana")), PLANNING_HORIZON);
    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(5, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    //5 activities with duration 1 and 1 activity already present with duration 5
    assertEquals(6, goalResult.satisfyingActivities().size());
  }

    @Test
  void testSingleActivityPlanSimpleCoexistenceGoalWithFunctionalParameters() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(new ActivityDirective(
            Duration.ZERO,
            "GrowBanana",
            Map.of(
                "quantity", SerializedValue.of(3),
                "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
            null,
            true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (growBananaActivity) => ActivityTemplates.PickBanana({quantity: growBananaActivity.parameters.quantity}),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PickBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of(3), peelBanana.serializedActivity().getArguments().get("quantity"));

    assertEquals(growBanana.startOffset().plus(growBananaDuration).plus(Duration.of(5, Duration.MINUTES)), peelBanana.startOffset());
  }

  @Test
  void testSingleActivityPlanSimpleCoexistenceGoalWithWindowReference() {
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true
            ),
            new ActivityDirective(
                Duration.MINUTE,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(Duration.MINUTE.in(MICROSECONDS))
                ),
                null,
                true
            )
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: Real.Resource("/fruit").lessThan(4),
            activityTemplate: (interval) => ActivityTemplates.GrowBanana({quantity: 10, growingDuration: interval.duration() }),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var biteBananas = planByActivityType.get("BiteBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, biteBananas.size());
    assertEquals(2, growBananas.size());
    final var iterator = growBananas.stream().sorted(Comparator.comparing(ActivityDirective::startOffset)).iterator();
    iterator.next();
    final var created = iterator.next();

    assertEquals(SerializedValue.of(10), created.serializedActivity().getArguments().get("quantity"));
    assertEquals(SerializedValue.of(Duration.of(2, Duration.MINUTES).in(MICROSECONDS)), created.serializedActivity().getArguments().get("growingDuration"));
    assertEquals(Duration.of(7, Duration.MINUTES), created.startOffset());
  }

  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    assertEquals(growBanana.startOffset().plus(growBananaDuration).plus(Duration.of(5, Duration.MINUTES)), peelBanana.startOffset());
  }

  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_constrainEndTime() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            endsWithin: TimingConstraint.range(WindowProperty.END, Operator.PLUS, Temporal.Duration.from( { minutes: 5 }))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    assertTrue(growBanana.startOffset().plus(growBananaDuration).plus(Duration.of(5, Duration.MINUTES)).noShorterThan(peelBanana.startOffset()));
    assertTrue(growBanana.startOffset().plus(growBananaDuration).noLongerThan(peelBanana.startOffset()));
  }

  /**
   * Allen Relation Before. GrowBanana finish between [5,10] before PeelBanana starts
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenBefore() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsWithin: TimingConstraint.bounds(TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5})), TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 10})))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    // Checking peelBanana starts between [5, 10] time units after growBanana finishes
    assertTrue(peelBanana.startOffset().noShorterThan(growBanana.startOffset().plus(growBananaDuration).plus(Duration.of(5, Duration.MINUTES))));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration).plus(Duration.of(10, Duration.MINUTES))));
  }

  /**
   * Allen Relation Equals. GrowBanana and DurationParameterActivity start and finish at the same time (they have equal durations)
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenEquals() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.DurationParameterActivity({duration: Temporal.Duration.from({ hours : 1})}),
            startsAt: TimingConstraint.singleton(WindowProperty.START),
            endsAt: TimingConstraint.singleton(WindowProperty.END)
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var durativeActivities = planByActivityType.get("DurationParameterActivity");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, durativeActivities.size());
    assertEquals(1, growBananas.size());
    final var durativeActivity = durativeActivities.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    // Checking both activities start at the same time
    assertTrue(durativeActivity.startOffset().isEqualTo(growBanana.startOffset()));

    // Checking both activities end at the same time
    final var activitytype = results.plan.getActivitiesByType().keySet().stream().filter(w->w.getName().equals("DurationParameterActivity")).findFirst().get();
    assertTrue(durativeActivity.startOffset().plus(results.plan.getActivitiesByType().get(activitytype).get(0).duration()).noShorterThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(10, Duration.MINUTES))));
  }

  /**
   * Allen Relation Meets. peelBanana activity starts at the time growBanana finishes
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenMeets() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.END)
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    // Checking start of peelBanana corresponds to end of growBanana
    assertTrue(peelBanana.startOffset().isEqualTo(growBanana.startOffset().plus(growBananaDuration)));
  }

  /**
   * Allen Relation Overlaps. DurationParameterActivity starts within [5,10] units of time before GrowBanana finishes
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenOverlaps() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.DurationParameterActivity({duration: Temporal.Duration.from({ hours : 1})}),
            startsWithin: TimingConstraint.bounds(TimingConstraint.singleton(WindowProperty.END).minus(Temporal.Duration.from({ minutes : 10})), TimingConstraint.singleton(WindowProperty.END).minus(Temporal.Duration.from({minutes : 5})))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var durationParameterActivities = planByActivityType.get("DurationParameterActivity");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, durationParameterActivities.size());
    assertEquals(1, growBananas.size());
    final var durationParameterActivity = durationParameterActivities.iterator().next();
    final var growBanana = growBananas.iterator().next();

    // Checking that DurationParameterActivity starts between [5,10] units of time before growBanana ends
    assertTrue(durationParameterActivity.startOffset().noShorterThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(10, Duration.MINUTES))));
    assertTrue(durationParameterActivity.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(5, Duration.MINUTES))));
  }

  /**
   * Allen Relation Contains. DurationParameterActivity starts within [5,10] units of time after growBanana starts and finishes within [5,10] units of time before growBanana finishes
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenContains() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
            BANANANATION,
            List.of(
                    new ActivityDirective(
                            Duration.of(5, Duration.MINUTES),
                            "GrowBanana",
                            Map.of(
                                    "quantity", SerializedValue.of(1),
                                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                            null,
                            true)),
            List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.DurationParameterActivity({duration: Temporal.Duration.from({ minutes : 50})}),
            startsWithin: TimingConstraint.bounds(TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5})), TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({minutes : 10}))),
            endsWithin: TimingConstraint.bounds(TimingConstraint.singleton(WindowProperty.END).minus(Temporal.Duration.from({ minutes : 10})), TimingConstraint.singleton(WindowProperty.END).minus(Temporal.Duration.from({ minutes : 5})))
          })
          """, true)),
            PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var durationParameterActivities = planByActivityType.get("DurationParameterActivity");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, durationParameterActivities.size());
    assertEquals(1, growBananas.size());
    final var durationParameterActivity = durationParameterActivities.iterator().next();
    final var growBanana = growBananas.iterator().next();


    // Checking left margin of Contains relation
    assertTrue(durationParameterActivity.startOffset().noShorterThan(growBanana.startOffset().plus(Duration.of(5, Duration.MINUTES))));
    assertTrue(durationParameterActivity.startOffset().noLongerThan(growBanana.startOffset().plus(Duration.of(10, Duration.MINUTES))));

    // Checking right margin of Contains relation
    final var activitytype = results.plan.getActivitiesByType().keySet().stream().filter(w->w.getName().equals("DurationParameterActivity")).findFirst();
    if (activitytype.isEmpty())
      fail("Could not find Coexistence Goal activity type");
    assertTrue(durationParameterActivity.startOffset().plus(results.plan.getActivitiesByType().get(activitytype.get()).get(0).duration()).noShorterThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(10, Duration.MINUTES))));
    assertTrue(durationParameterActivity.startOffset().plus(results.plan.getActivitiesByType().get(activitytype.get()).get(0).duration()).noLongerThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(5, Duration.MINUTES))));
  }

  /**
   * Allen Relation Starts. peelBanana starts within [5, 10] units of time after growBanana starts
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenStarts() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsWithin: TimingConstraint.bounds(TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5})), TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 10})))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();

    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    // Checking that peelBanana starts at the same time as growBanana
    assertTrue(peelBanana.startOffset().noShorterThan(growBanana.startOffset().plus(Duration.of(5, Duration.MINUTES))));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(Duration.of(10, Duration.MINUTES))));
  }


  /**
   * Allen Relation Contains. DurationParameterActivity finishes at the same time as growBanana
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenFinishesAt() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.DurationParameterActivity({duration: Temporal.Duration.from({ minutes : 50})}),
            endsAt: TimingConstraint.singleton(WindowProperty.END)
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var durationParameterActivities = planByActivityType.get("DurationParameterActivity");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, durationParameterActivities.size());
    assertEquals(1, growBananas.size());
    final var durationParameterActivity = durationParameterActivities.iterator().next();
    final var growBanana = growBananas.iterator().next();


    // Checking that durationParameterActivities finishes at the same time as growBanana
    final var activitytype = results.plan.getActivitiesByType().keySet().stream().filter(w->w.getName().equals("DurationParameterActivity")).findFirst();
    if (activitytype.isEmpty())
      fail("Could not find Coexistence Goal activity type");
    assertTrue(durationParameterActivity.startOffset().plus(results.plan.getActivitiesByType().get(activitytype.get()).get(0).duration()).isEqualTo(growBanana.startOffset().plus(growBananaDuration)));
  }

  /**
   * Allen Relation Contains. DurationParameterActivity finishes within [5, 10] units of time before growBanana finishes
   */
  @Test
  void testSingleActivityPlanSimpleCoexistenceGoal_AllenFinishesWithin() {
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(5, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: (span) => ActivityTemplates.DurationParameterActivity({duration: Temporal.Duration.from({ minutes : 50})}),
            endsWithin: TimingConstraint.bounds(TimingConstraint.singleton(WindowProperty.END).minus(Temporal.Duration.from({ minutes : 10})), TimingConstraint.singleton(WindowProperty.END).minus(Temporal.Duration.from({ minutes : 5})))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var durationParameterActivities = planByActivityType.get("DurationParameterActivity");
    final var growBananas = planByActivityType.get("GrowBanana");
    assertEquals(1, durationParameterActivities.size());
    assertEquals(1, growBananas.size());
    final var durationParameterActivity = durationParameterActivities.iterator().next();
    final var growBanana = growBananas.iterator().next();


    // Checking that durationParameterActivities finishes at the same time as growBanana
    final var activitytype = results.plan.getActivitiesByType().keySet().stream().filter(w->w.getName().equals("DurationParameterActivity")).findFirst();
    if (activitytype.isEmpty())
      fail("Could not find Coexistence Goal activity type");
    assertTrue(durationParameterActivity.startOffset().plus(results.plan.getActivitiesByType().get(activitytype.get()).get(0).duration()).noShorterThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(10, Duration.MINUTES))));
    assertTrue(durationParameterActivity.startOffset().plus(results.plan.getActivitiesByType().get(activitytype.get()).get(0).duration()).noLongerThan(growBanana.startOffset().plus(growBananaDuration).minus(Duration.of(5, Duration.MINUTES))));
  }

  @Test
  void testStateCoexistenceGoal_greaterThan() {
    // Initial plant count is 200 in default configuration
    // GrowBanana adds 100
    // PickBanana removes 100
    // Between the end of the GrowBanana, and the beginning of the PickBanana, the StateConstraint is satisfied
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(BANANANATION, List.of(
        new ActivityDirective(
            Duration.of(2, HOURS),
            "GrowBanana",
            Map.of(
                "quantity", SerializedValue.of(100),
                "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
            null,
            true),
        new ActivityDirective(
            Duration.of(4, HOURS),
            "PickBanana",
            Map.of("quantity", SerializedValue.of(100)),
            null,
            true)), List.of(new EdslGoal(new GoalId(0L, 0L), """
         export default (): Goal => {
          return Goal.CoexistenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            forEach: Real.Resource("/plant").greaterThan(201.0),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
        }""", true)), PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(growBanana.startOffset().plus(growBananaDuration)));
    assertTrue(peelBanana.startOffset().noLongerThan(pickBanana.startOffset()));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testLessThan() {
    // Initial plant count is 200 in default configuration
    // GrowBanana adds 100
    // PickBanana removes 100
    // Between the end of the GrowBanana, and the beginning of the PickBanana, the StateConstraint is satisfied
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(100)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: Real.Resource("/plant").lessThan(199.0),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(pickBanana.startOffset()), "PeelBanana was placed at %s which is before the start/end of PickBanana at %s".formatted(peelBanana.startOffset(), pickBanana.startOffset()));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration)), "PeelBanana was placed at %s which is after the end of GrowBanana at %s".formatted(peelBanana.startOffset(), growBanana.startOffset()));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testLinear_atChangePoints() {
    // Initial fruit count is 4.0 in default configuration
    // BiteBanana takes away 1.0
    // The constraint should be satisfied between the two BiteBananaActivities
    final var results = runScheduler(BANANANATION, List.of(
        new ActivityDirective(
            Duration.of(2, HOURS),
            "BiteBanana",
            Map.of("biteSize", SerializedValue.of(1.0)),
            null,
            true),
        new ActivityDirective(
            Duration.of(4, HOURS),
            "BiteBanana",
            Map.of("biteSize", SerializedValue.of(1.0)),
            null,
            true)
    ), List.of(new EdslGoal(new GoalId(0L, 0L), """
         export default (): Goal => {
          return Goal.CoexistenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            forEach: Windows.And(
              Real.Resource("/fruit").lessThan(4.0),
              Real.Resource("/fruit").greaterThan(2.0)
            ),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
        }""", true)), PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(Duration.of(2, HOURS)), "PeelBanana was placed at %s which is before the start/end of BiteBanana at %s".formatted(peelBanana.startOffset(), Duration.of(2, HOURS)));
    assertTrue(peelBanana.startOffset().noLongerThan(Duration.of(4, HOURS)), "PeelBanana was placed at %s which is before the start/end of BiteBanana at %s".formatted(peelBanana.startOffset(), Duration.of(4, HOURS)));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testLinear_interpolated() {
    // Initial fruit count is 4.0 in default configuration
    // GrowBanana takes it from 4.0 to 7.0 in 3 hours
    // The constraint should be satisfied between the two BiteBananaActivities
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(1, HOURS),
                "GrowBanana",
                Map.of("quantity", SerializedValue.of(3.0),
                       "growingDuration", SerializedValue.of(Duration.of(3, HOURS).in(Duration.MICROSECONDS))),
                null,
                true)),

        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromTip"}),
                   forEach: Windows.And(
                     Real.Resource("/fruit").greaterThan(5.0),
                     Real.Resource("/fruit").lessThan(6.0),
                   ),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(2, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(Duration.of(2, HOURS)), "PeelBanana was placed at %s which is before the start/end of GrowBanana at %s".formatted(peelBanana.startOffset(), Duration.of(2, HOURS)));
    assertTrue(peelBanana.startOffset().noLongerThan(Duration.of(3, HOURS)), "PeelBanana was placed at %s which is before the start/end of GrowBanana at %s".formatted(peelBanana.startOffset(), Duration.of(3, HOURS)));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromTip")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testEqualTo_satsified() {
    // Initial plant count is 200 in default configuration
    // PickBanana removes 100
    // GrowBanana adds 100
    // Between the end of the PickBanana, and the beginning of the GrowBanana, the StateConstraint is satisfied
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(100)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: Real.Resource("/plant").equal(100.0),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(pickBanana.startOffset()), "PeelBanana was placed at %s which is before the start/end of PickBanana at %s".formatted(peelBanana.startOffset(), pickBanana.startOffset()));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration)), "PeelBanana was placed at %s which is after the end of GrowBanana at %s".formatted(peelBanana.startOffset(), growBanana.startOffset()));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testEqualTo_neverSatisfied() {
    // Initial plant count is 200 in default configuration
    // PickBanana removes 99
    // GrowBanana adds 100
    // The StateConstraint is never satisfied
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(99)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: Real.Resource("/plant").equal(100.0),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(2, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    assertEquals(Duration.of(2, HOURS), pickBanana.startOffset());
    assertEquals(Duration.of(4, HOURS), growBanana.startOffset());
  }

  private static List<ActivityDirective> onePickEveryTenMinutes(final Interval interval){
    final var plan = new ArrayList<ActivityDirective>();
    for(var cur = interval.start; cur.shorterThan(interval.end); cur = cur.plus(Duration.of(10, MINUTES))){
      plan.add(new ActivityDirective(
          cur,
          "PickBanana",
          Map.of("quantity", SerializedValue.of(100)),
          null,
          true));
    }
    return plan;
  }

  @Test
  @Disabled
  void testBigCoexistence(){
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        onePickEveryTenMinutes(PLANNING_HORIZON.getHor()),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: ActivityExpression.ofType(ActivityTypes.PickBanana),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);
    assertEquals(1152, results.updatedPlan().size());
  }

  @Test
  void testNotEqualTo_satisfied() {
    // Initial plant count is 200 in default configuration
    // PickBanana removes 100
    // GrowBanana adds 100
    // The StateConstraint is satisfied between the two
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(100)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: Real.Resource("/plant").notEqual(200.0),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(pickBanana.startOffset()), "PeelBanana was placed at %s which is before the start/end of PickBanana at %s".formatted(peelBanana.startOffset(), pickBanana.startOffset()));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration)), "PeelBanana was placed at %s which is after the end of GrowBanana at %s".formatted(peelBanana.startOffset(), growBanana.startOffset()));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testBetweenInTermsOfAnd() {
    // Initial plant count is 200 in default configuration
    // PickBanana removes 100
    // GrowBanana adds 100
    // The StateConstraint is satisfied between the two
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(100)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: Windows.And(
                     Real.Resource("/plant").greaterThan(50.0),
                     Real.Resource("/plant").lessThan(150.0),
                   ),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(pickBanana.startOffset()), "PeelBanana was placed at %s which is before the start/end of PickBanana at %s".formatted(peelBanana.startOffset(), pickBanana.startOffset()));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration)), "PeelBanana was placed at %s which is after the end of GrowBanana at %s".formatted(peelBanana.startOffset(), growBanana.startOffset()));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testWindowsOr() {
    // Initial plant count is 200 in default configuration
    // PickBanana removes 100
    // GrowBanana adds 100
    // Between the end of the PickBanana, and the beginning of the GrowBanana, the StateConstraint is satisfied
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(100)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                   activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                   forEach: Windows.Or(
                     Real.Resource("/plant").equal(999.0),
                     Real.Resource("/plant").equal(100.0),
                   ),
                   startsAt: TimingConstraint.singleton(WindowProperty.START)
                 })
               }""", true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    assertTrue(peelBanana.startOffset().noShorterThan(pickBanana.startOffset()), "PeelBanana was placed at %s which is before the start/end of PickBanana at %s".formatted(peelBanana.startOffset(), pickBanana.startOffset()));
    assertTrue(peelBanana.startOffset().noLongerThan(growBanana.startOffset().plus(growBananaDuration)), "PeelBanana was placed at %s which is after the end of GrowBanana at %s".formatted(peelBanana.startOffset(), growBanana.startOffset()));
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testWindowsTransition() {
    // Initial producer is "Chiquita"
    // ChangeProducer sets producer to "Dole"
    // The PeelBanana should be placed at the same time as the ChangeProducer
    final var results = runScheduler(BANANANATION, List.of(
        new ActivityDirective(
            Duration.of(2, HOURS),
            "ChangeProducer",
            Map.of(),
            null,
            true)), List.of(new EdslGoal(new GoalId(0L, 0L), """
         export default (): Goal => {
          return Goal.CoexistenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            forEach: Discrete.Resource(Resources["/producer"]).transition("Chiquita", "Dole"),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
        }""", true)), PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(2, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var changeProducer = planByActivityType.get("ChangeProducer").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    assertEquals(changeProducer.startOffset(), peelBanana.startOffset());
    assertEquals(Map.of("peelDirection", SerializedValue.of("fromStem")), peelBanana.serializedActivity().getArguments());
  }

  @Test
  void testWindowsTransition_unsatisfied() {
    // Initial producer is "Chiquita"
    // ChangeProducer sets producer to "Dole"
    // The PeelBanana should be placed at the same time as the ChangeProducer
    final var results = runScheduler(BANANANATION, List.of(
        new ActivityDirective(
            Duration.of(2, HOURS),
            "ChangeProducer",
            Map.of("producer", SerializedValue.of("Fyffes")),
            null,
            true)), List.of(new EdslGoal(new GoalId(0L, 0L), """
         export default (): Goal => {
          return Goal.CoexistenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            forEach: Discrete.Resource(Resources["/producer"]).transition("Chiquita", "Dole"),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
        }""", true)), PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(1, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var changeProducer = planByActivityType.get("ChangeProducer").iterator().next();
    assertEquals("Fyffes", changeProducer.serializedActivity().getArguments().get("producer").asString().get());
  }

  @Test
  void testExternalResource() {

    final var myBooleanResource = new DiscreteProfile(
        List.of(
            new Segment<>(Interval.between(HOURS.times(2), HOURS.times(4)), SerializedValue.of(true))
        )
    ).assignGaps(new DiscreteProfile(List.of(new Segment<>(Interval.FOREVER, SerializedValue.of(false)))));

    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
         export default (): Goal => {
          return Goal.CoexistenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            forEach: Discrete.Resource(Resources["/my_boolean"]).equal(true),
            startsAt: TimingConstraint.singleton(WindowProperty.START)
          })
        }""", true)),
        List.of(),
        PLANNING_HORIZON,
        Optional.of(
            new ExternalProfiles(
                Map.of(),
                Map.of("/my_boolean", myBooleanResource),
                List.of(new ResourceType("/my_boolean", new ValueSchema.BooleanSchema()))))
    );

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(1, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    assertEquals(HOURS.times(2), peelBanana.startOffset());
  }

  @Test
  void testApplyWhen() {
    final var growBananaDuration = Duration.of(1, SECONDS);

    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(1, SECONDS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirective(
                Duration.of(2, SECONDS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirective(
                Duration.of(3, SECONDS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirective(
                Duration.of(48, HOURS),
                "PickBanana",
                Map.of(
                    "quantity", SerializedValue.of(100000)
                ),
                null,
                true)
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                  export default () => Goal.ActivityRecurrenceGoal({
                      activityTemplate: ActivityTemplates.ChangeProducer({producer: "Morpheus"}),
                      interval: Temporal.Duration.from({ hours : 24 })
                    }).applyWhen(Real.Resource("/plant").greaterThan(1.0))""", true)
        ),
        PLANNING_HORIZON
    );

    for (ActivityDirective i : results.updatedPlan().stream().toList()) {
      System.out.println(i.serializedActivity().getTypeName() + ": " + i.startOffset().toString());
    }

    assertEquals(1, results.scheduleResults.goalResults().size()); //starts an instant before 12:00 (when there is more than 1 plant - the interval is about 00:00-24:00 because the plant gets created early, then the interval is subdivided, and then a interval is picked, seemingly arbitrarily, as this is uncontrollable duration (see line 304 of ActivityCreationTemplate), after which cadence is maintained) then schedules 4, as expected. 7 total then.
    assertEquals(6, results.updatedPlan().size());

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var changeProducer = planByActivityType.get("ChangeProducer").iterator().next();
    assertEquals("Morpheus", changeProducer.serializedActivity().getArguments().get("producer").asString().get());
  }

  @Test
  void testGlobalSchedulingConditions_conditionNeverOccurs() {
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.ChangeProducer({producer: "Morpheus"}),
            interval: Temporal.Duration.from({days: 1})
          })
          """, true)
        ),
        List.of(
            new SchedulingConditionRecord(
                new SchedulingConditionId(0L),
                0L,
                "fruit greater than 5",
                new SchedulingConditionSource("""
                export default () => GlobalSchedulingCondition.scheduleActivitiesOnlyWhen(Real.Resource(\"/fruit\").greaterThan(5.0))
                """)),
            createAutoMutex("ChangeProducer")
        ),
        PLANNING_HORIZON);
    assertEquals(0, results.updatedPlan().size());
  }

  @Test
  void testGlobalSchedulingConditions_conditionAlwaysTrue() {
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.ChangeProducer({producer: "Morpheus"}),
            interval: Temporal.Duration.from({days: 1})
          })
          """, true)
        ),
        List.of(
            new SchedulingConditionRecord(
                new SchedulingConditionId(0L),
                0L,
                "fruit less than 5",
                new SchedulingConditionSource("export default () => GlobalSchedulingCondition.scheduleActivitiesOnlyWhen(Real.Resource(\"/fruit\").lessThan(5.0))"))
        ),
        PLANNING_HORIZON);
    assertEquals(4, results.updatedPlan().size());
  }

  @Test
  void testGlobalSchedulingConditions_conditionSometimesTrue() {
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(24, HOURS).minus(MICROSECONDS),
                "BiteBanana",
                Map.of("biteSize", SerializedValue.of(1)),
                null,
                true)
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.ChangeProducer({producer: "Morpheus"}),
            interval: Temporal.Duration.from({days: 1})
          })
          """, true)
        ),
        List.of(
            new SchedulingConditionRecord(
                 new SchedulingConditionId(0L),
                0L,
                "fruit greater than 3.5",
                new SchedulingConditionSource("export default () => GlobalSchedulingCondition.scheduleActivitiesOnlyWhen(Real.Resource(\"/fruit\").greaterThan(3.5))"))
        ),
        PLANNING_HORIZON);
    assertEquals(2, results.updatedPlan().size());
  }

  private static Map<String, Collection<ActivityDirective>>
  partitionByActivityType(final Iterable<ActivityDirective> activities) {
    final var result = new HashMap<String, Collection<ActivityDirective>>();
    for (final var activity : activities) {
      result
          .computeIfAbsent(activity.serializedActivity().getTypeName(), key -> new ArrayList<>())
          .add(activity);
    }
    return result;
  }

  private static Map<Duration, Collection<ActivityDirective>>
  partitionByStartTime(final Iterable<ActivityDirective> activities) {
    final var result = new HashMap<Duration, Collection<ActivityDirective>>();
    for (final var activity : activities) {
      result
          .computeIfAbsent(activity.startOffset(), key -> new ArrayList<>())
          .add(activity);
    }
    return result;
  }

  private static MockMerlinDatabaseService.MissionModelInfo getMissionModelInfo(final MissionModelDescription desc) {
    final var jarFile = getLatestJarFile(desc.libPath());
    try {
      return new MockMerlinDatabaseService.MissionModelInfo(
          desc.libPath(),
          Path.of(jarFile.getName()),
          desc.name(),
          loadMissionModelTypesFromJar(jarFile.getAbsolutePath(), desc.config()),
          desc.config());
    } catch (MissionModelLoader.MissionModelLoadException e) {
      fail(e);
      throw new IllegalStateException("unreachable due to fail() call");
    }
  }

  private static File getLatestJarFile(final Path libPath) {
    final var files = libPath.toFile().listFiles(pathname -> pathname.getName().endsWith(".jar"));
    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
    return files[0];
  }

  public static SchedulingConditionRecord createAutoMutex(String activityType){
    return new SchedulingConditionRecord(
        new SchedulingConditionId(0L),
        0L,
        "auto-mutex activity type "+activityType,
        new SchedulingConditionSource("""
                    export default function myCondition() {
                      return GlobalSchedulingCondition.mutex([ActivityTypes.%s], [ActivityTypes.%s])
                    }
                    """.formatted(activityType, activityType)));

  }

  private SchedulingRunResults runScheduler(
      final MissionModelDescription desc,
      final List<ActivityDirective> plannedActivities,
      final Iterable<EdslGoal> goals,
      final PlanningHorizon planningHorizon){
    return runScheduler(desc, plannedActivities, goals, planningHorizon, 30);
  }

  private SchedulingRunResults runScheduler(
      final MissionModelDescription desc,
      final List<ActivityDirective> plannedActivities,
      final Iterable<EdslGoal> goals,
      final PlanningHorizon planningHorizon,
      final int cachedEngineStoreCapacity
  )
  {
    final var activities = new HashMap<ActivityDirectiveId, ActivityDirective>();
    long id = 1;
    for (final var activityDirective : plannedActivities) {
      activities.put(new ActivityDirectiveId(id++), activityDirective);
    }
    return runScheduler(desc, activities, goals, List.of(), planningHorizon, Optional.empty(), cachedEngineStoreCapacity);
  }

  private SchedulingRunResults runScheduler(
      final MissionModelDescription desc,
      final Map<ActivityDirectiveId, ActivityDirective> plannedActivities,
      final Iterable<EdslGoal> goals,
      final PlanningHorizon planningHorizon
  )
  {
    return runScheduler(desc, plannedActivities, goals, List.of(), planningHorizon, Optional.empty(), 30);
  }

  private SchedulingRunResults runScheduler(
      final MissionModelDescription desc,
      final List<ActivityDirective> plannedActivities,
      final Iterable<EdslGoal> goals,
      final List<SchedulingConditionRecord> globalSchedulingConditions,
      final PlanningHorizon planningHorizon
  ){
    return runScheduler(desc, plannedActivities, goals, globalSchedulingConditions, planningHorizon, Optional.empty());
  }

  private SchedulingRunResults runScheduler(
      final MissionModelDescription desc,
      final List<ActivityDirective> plannedActivities,
      final Iterable<EdslGoal> goals,
      final List<SchedulingConditionRecord> globalSchedulingConditions,
      final PlanningHorizon planningHorizon,
      final Optional<ExternalProfiles> externalProfiles
  ){
    final var activities = new HashMap<ActivityDirectiveId, ActivityDirective>();
    long id = 1;
    for (final var activityDirective : plannedActivities) {
      activities.put(new ActivityDirectiveId(id++), activityDirective);
    }
    return runScheduler(desc, activities, goals, globalSchedulingConditions, planningHorizon, externalProfiles, 30);
  }

  private SchedulingRunResults runScheduler(
      final MissionModelDescription desc,
      final Map<ActivityDirectiveId, ActivityDirective> plannedActivities,
      final Iterable<EdslGoal> goals,
      final List<SchedulingConditionRecord> globalSchedulingConditions,
      final PlanningHorizon planningHorizon,
      final Optional<ExternalProfiles> externalProfiles,
      final int cachedEngineStoreCapacity
  ) {
    final var mockMerlinService = new MockMerlinDatabaseService();
    mockMerlinService.setMissionModel(getMissionModelInfo(desc));
    mockMerlinService.setInitialPlan(plannedActivities);
    mockMerlinService.setPlanningHorizon(planningHorizon);
    externalProfiles.ifPresent(mockMerlinService::setExternalDataset);
    final var planId = new PlanId(1L);
    final var goalsByPriority = new ArrayList<GoalRecord>();

    for (final var goal : goals) {
      goalsByPriority.add(new GoalRecord(goal.goalId(), "test goal", new GoalType.EDSL(goal.definition()), goal.simulateAfter()));
    }
    final var specificationService = new SpecificationService(new MockSpecificationRepository(Map.of(new SpecificationId(1L), new Specification(
        new SpecificationId(1L),
        1L,
        planId,
        1L,
        new Timestamp(planningHorizon.getStartInstant()),
        new Timestamp(planningHorizon.getEndInstant()),
        Map.of(),
        false,
        goalsByPriority,
        globalSchedulingConditions))));
    final var agent = new SynchronousSchedulerAgent(
        specificationService,
        mockMerlinService,
        desc.libPath(),
        PlanOutputMode.UpdateInputPlanWithNewActivities,
        schedulingDSLCompiler);
    // Scheduling Goals -> Scheduling Specification
    final var writer = new MockResultsProtocolWriter();
    agent.schedule(new ScheduleRequest(new SpecificationId(1L), new SpecificationRevisionData(1L, 1L)), writer, () -> false, cachedEngineStoreCapacity);
    assertEquals(1, writer.results.size());
    final var result = writer.results.get(0);
    if (result instanceof MockResultsProtocolWriter.Result.Failure e) {
      final var serializedReason = SchedulerParsers.scheduleFailureP.unparse(e.reason()).toString();
      System.err.println(serializedReason);
      fail(serializedReason);
    }
    return new SchedulingRunResults(
        ((MockResultsProtocolWriter.Result.Success) result).results(),
        mockMerlinService.updatedPlan,
        mockMerlinService.plan,
        plannedActivities);
  }

  record SchedulingRunResults(
      ScheduleResults scheduleResults,
      Collection<ActivityDirective> updatedPlan,
      Plan plan,
      Map<ActivityDirectiveId, ActivityDirective> idToAct) {}

  static MerlinDatabaseService.MissionModelTypes loadMissionModelTypesFromJar(
      final String jarPath,
      final Map<String, SerializedValue> configuration)
  throws MissionModelLoader.MissionModelLoadException
  {
    final var missionModel = MissionModelLoader.loadMissionModel(
        Instant.EPOCH,
        SerializedValue.of(configuration),
        Path.of(jarPath),
        "",
        "");
    final Map<String, ? extends DirectiveType<?, ?, ?>> taskSpecTypes = missionModel.getDirectiveTypes().directiveTypes();
    final var activityTypes = new ArrayList<gov.nasa.jpl.aerie.scheduler.server.models.ActivityType>();
    for (final var entry : taskSpecTypes.entrySet()) {
      final var activityTypeName = entry.getKey();
      final var taskSpecType = entry.getValue();
      activityTypes.add(new gov.nasa.jpl.aerie.scheduler.server.models.ActivityType(
          activityTypeName,
          taskSpecType
              .getInputType()
              .getParameters()
              .stream()
              .collect(Collectors.toMap(Parameter::name, Parameter::schema)),
          Map.of()
      ));
    }

    final var resourceTypes = new ArrayList<ResourceType>();
    for (final var entry : missionModel.getResources().entrySet()) {
      final var name = entry.getKey();
      final var resource = entry.getValue();
      resourceTypes.add(new ResourceType(name, resource.getOutputType().getSchema()));
    }

    return new MerlinDatabaseService.MissionModelTypes(activityTypes, resourceTypes);
  }

  @Test
  void testAndFailure(){
    //The cardinality goal is not satisfiable but the partial satisfaction is on by default so as the second subgoal is satisfied
    //the composite goal is not satified but does not backtrack
    final var growBananaDuration = Duration.of(1, Duration.MINUTES);
    final var planningHorizon = new PlanningHorizon(TimeUtility.fromDOY("2021-001T00:00:00"),
        TimeUtility.fromDOY("2021-001T01:00:00"));
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(1, Duration.MINUTES),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(99)),
                null,
                true),
            new ActivityDirective(
                Duration.of(15, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CardinalityGoal({
                                      activityTemplate: ActivityTemplates.GrowBanana({
                                        quantity: 1,
                                        growingDuration: Temporal.Duration.from({ minutes : 30 }),
                                      }),
                                      specification : {duration: Temporal.Duration.from({ hours: 4 })}
                                      })
                        .and(
                           Goal.CoexistenceGoal({
                             activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                             forEach: ActivityExpression.ofType(ActivityTypes.PickBanana),
                             startsAt: TimingConstraint.singleton(WindowProperty.START)
                           })
                        )
               }""", true)),
        List.of(
            createAutoMutex("GrowBanana"),
            createAutoMutex("PeelBanana")
        ),
        planningHorizon);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertEquals(4, results.updatedPlan().size());
    assertFalse(results.scheduleResults.goalResults().entrySet().iterator().next().getValue().satisfied());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    final var growStartTimes = new HashSet<Duration>();
    planByActivityType.get("GrowBanana").forEach(activity -> growStartTimes.add(activity.startOffset()));
    assertEquals(Duration.of(1, Duration.MINUTES), pickBanana.startOffset());
    assertEquals(Duration.of(1, Duration.MINUTES), peelBanana.startOffset());
    assertTrue(growStartTimes.contains(Duration.of(15, Duration.MINUTES)));
    assertTrue(growStartTimes.contains(Duration.of(16, Duration.MINUTES)));
  }

  @Test
  void testOrFailure(){
    //the two goals are unsatisfiable so the supergoal will not appear satisfied.
    //partial satisfaction is activated by default so the solver does not backtrack on insertion of activities
    final var growBananaDuration = Duration.of(1, Duration.MINUTES);
    final var planningHorizon = new PlanningHorizon(TimeUtility.fromDOY("2021-001T00:00:00"),
                                                    TimeUtility.fromDOY("2021-001T01:00:00"));
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, Duration.MINUTES),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(99)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, Duration.MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CardinalityGoal({
                                      activityTemplate: ActivityTemplates.GrowBanana({
                                        quantity: 1,
                                       growingDuration: Temporal.Duration.from({ minutes : 15 }),
                                      }),
                                      specification : { duration: Temporal.Duration.from({ hours : 4 }) }
                                      })
                        .or(
                           Goal.CardinalityGoal({
                                      activityTemplate: ActivityTemplates.GrowBanana({
                                        quantity: 1,
                                        growingDuration: Temporal.Duration.from({ minutes : 30}),
                                      }),
                                      specification : {duration: Temporal.Duration.from({ hours : 4 })}
                                      })
                        )
               }""", true)),
        List.of(
            createAutoMutex("GrowBanana"),
            createAutoMutex("PeelBanana")
        ),
        planningHorizon);
    assertEquals(1, results.scheduleResults.goalResults().size());
    //as goal is partially satisfiable, it does not remove activities from the plan
    assertEquals(5, results.updatedPlan().size());
    assertFalse(results.scheduleResults.goalResults().entrySet().iterator().next().getValue().satisfied());
  }

  @Test
  void testOr(){
    //the first subgoal is satisfied which prevents the second subgoal to be looked at
    //overall, the or goal is satisfied.
    final var growBananaDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.of(2, HOURS),
                "PickBanana",
                Map.of("quantity", SerializedValue.of(99)),
                null,
                true),
            new ActivityDirective(
                Duration.of(4, HOURS),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(100),
                    "growingDuration", SerializedValue.of(growBananaDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
                export default (): Goal => {
                 return Goal.CoexistenceGoal({
                             activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                             forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
                             startsAt: TimingConstraint.singleton(WindowProperty.START)
                           })
                        .or(
                           Goal.CoexistenceGoal({
                             activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                             forEach: ActivityExpression.ofType(ActivityTypes.PickBanana),
                             startsAt: TimingConstraint.singleton(WindowProperty.START)
                           })
                        )
               }""", true)),
        List.of(
            createAutoMutex("GrowBanana"),
            createAutoMutex("PeelBanana")
        ),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertTrue(results.scheduleResults.goalResults().entrySet().iterator().next().getValue().satisfied());
    assertEquals(3, results.updatedPlan().size());
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var pickBanana = planByActivityType.get("PickBanana").iterator().next();
    final var growBanana = planByActivityType.get("GrowBanana").iterator().next();
    final var peelBanana = planByActivityType.get("PeelBanana").iterator().next();
    assertEquals(Duration.of(2, HOURS), pickBanana.startOffset());
    assertEquals(Duration.of(4, HOURS), peelBanana.startOffset());
    assertEquals(Duration.of(4, HOURS), growBanana.startOffset());
  }

  @Test
  void testTreeActivities(){
    final var goalDefinition = """
        export default function myGoal() {
                          return Goal.CoexistenceGoal({
                            forEach: ActivityExpression.ofType(ActivityType.child),
                            activityTemplate: ActivityTemplates.BiteBanana({
                              biteSize: 1,
                            }),
                            startsAt:TimingConstraint.singleton(WindowProperty.START)
                          })
                        }
        """;
    final var planningHorizon = new PlanningHorizon(TimeUtility.fromDOY("2021-001T00:00:00"),
                                                    TimeUtility.fromDOY("2021-200T01:00:00"));
    final var results = runScheduler(BANANANATION,
                 List.of(
                     new ActivityDirective(
                         Duration.of(1, HOURS),
                         "parent",
                         Map.of(),
                         null,
                         true)),
                 List.of(new EdslGoal(new GoalId(0L, 0L), goalDefinition, true)),
                 planningHorizon);
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var biteBanana = planByActivityType.get("BiteBanana").stream().map((bb) -> bb.startOffset()).toList();
    assertEquals(biteBanana.size(), 2);
  }

  @Test
  void testAutoSatisfaction(){
    final var goalDefinition = """
        export default function myGoal() {
                          return Goal.CoexistenceGoal({
                            forEach: ActivityExpression.ofType(ActivityType.parent),
                            activityTemplate: ActivityTemplates.child({
                              counter: 1,
                            }),
                            startsAt:TimingConstraint.singleton(WindowProperty.START)
                          })
                        }
        """;
    final var planningHorizon = new PlanningHorizon(TimeUtility.fromDOY("2021-001T00:00:00"),
                                                    TimeUtility.fromDOY("2021-200T01:00:00"));
    final var results = runScheduler(BANANANATION,
                                     List.of(
                                         new ActivityDirective(
                                             Duration.of(1, HOURS),
                                             "parent",
                                             Map.of(),
                                             null,
                                             true)),
                                     List.of(new EdslGoal(new GoalId(0L, 0L), goalDefinition, true)),
                                     List.of(),
                                     planningHorizon);
    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var parentActs = planByActivityType.get("parent");
    final var childActs = planByActivityType.get("child").stream().map(ActivityDirective::startOffset).toList();
    //goal should be satisfied
    assertTrue(results.scheduleResults.goalResults().entrySet().iterator().next().getValue().satisfied());
    //ensure no new child activity has been inserted
    assertEquals(2, childActs.size());
    //ensure no new parent activity has been inserted
    assertEquals(1, parentActs.size());
    for(final var parentAct: parentActs){
      assertTrue(childActs.contains(parentAct.startOffset()));
    }
  }

  @Test
  void testDurationParameter() {
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
        export default function myGoal() {
          return Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.DurationParameterActivity({
              duration: Temporal.Duration.from({seconds: 1}),
            }),
            interval: Temporal.Duration.from({hours: 1})
          })
        }
          """, true)),
        PLANNING_HORIZON);
    assertEquals(96, results.updatedPlan().size());
  }

  @Test
  void testUnfinishedActivity(){
    final PlanningHorizon PLANNING_HORIZON = new PlanningHorizon(
        TimeUtility.fromDOY("2025-090T00:00:00"),
        TimeUtility.fromDOY("2025-134T00:00:00"));
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default (): Goal => {
                           return Goal.ActivityRecurrenceGoal({
                             activityTemplate: ActivityTemplates.parent({
                               label: "label"
                           }),
                             interval: Temporal.Duration.from({ days: 30})
                           })
                       }
            """, true)), PLANNING_HORIZON);
    //parent takes much more than 134 - 90 = 44 days to finish
    assertEquals(0, results.updatedPlan.size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));
    assertFalse(goalResult.satisfied());
  }

  /**
   * If you passed activities without duration to the scheduler in an initial plan, it would fail
   */
  @Test
  public void testBugDurationInMicroseconds(){
    final var results = runScheduler(
        BANANANATION,
        List.of(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
            export default (): Goal =>
              Goal.ActivityRecurrenceGoal({
                activityTemplate: ActivityTemplates.BakeBananaBread({ temperature: 325.0, tbSugar: 2, glutenFree: false }),
                interval: Temporal.Duration.from({ hours: 12 }),
              });
            """, true)),
        PLANNING_HORIZON);
    runScheduler(
        BANANANATION,
        results.updatedPlan.stream().toList(),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
            export default (): Goal =>
              Goal.ActivityRecurrenceGoal({
                activityTemplate: ActivityTemplates.BakeBananaBread({ temperature: 325.0, tbSugar: 2, glutenFree: false }),
                interval: Temporal.Duration.from({ hours: 12 }),
              });
            """, true)),
        PLANNING_HORIZON);
    assertEquals(8, results.updatedPlan().size());
  }

  @Test
  void test_inf_loop(){
    final var results = runScheduler(
        BANANANATION,
        List.of(new ActivityDirective(
            Duration.of(23, HOURS),
            "BiteBanana",
            Map.of("biteSize", SerializedValue.of(10)),
            null,
            true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
              export default (): Goal =>
                 Goal.ActivityRecurrenceGoal({
                   activityTemplate: ActivityTemplates.BakeBananaBread({ temperature: 325.0, tbSugar: 2, glutenFree: false }),
                   interval: Temporal.Duration.from({ hours: 2 }),
                 });
            """, true)),
        List.of(new SchedulingConditionRecord(new SchedulingConditionId(0), 0L, "fruit less than 3", new SchedulingConditionSource(
            """
        export default function myFirstSchedulingCondition(): GlobalSchedulingCondition {
          return GlobalSchedulingCondition.scheduleActivitiesOnlyWhen(Real.Resource('/fruit').lessThan(3.0));
        }
        """
        ))),
        new PlanningHorizon(
            TimeUtility.fromDOY("2022-318T00:00:00"),
            TimeUtility.fromDOY("2022-319T00:00:00")));
    assertEquals(2, results.updatedPlan().size());
  }

  /**
   * Test that the scheduler can correctly place activities off of activities anchored to start at
   * the end of another activity.
   */
  @Test
  void testRelativeActivityPlanZeroStartOffsetEnd() {
    /*
    Start with a plan with B anchored to end of A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
    */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                Duration.ZERO,
                "DurationParameterActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                Duration.ZERO,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                new ActivityDirectiveId(1L),
                false),
            new ActivityDirectiveId(3L),
            new ActivityDirective(
                Duration.of(5, MINUTES),
                "ControllableDurationActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                new ActivityDirectiveId(2L),
                false)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.END).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    final var durationParamActivities = planByActivityType.get("DurationParameterActivity");
    final var controllableDurationActivities = planByActivityType.get("ControllableDurationActivity");

    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    assertEquals(1, durationParamActivities.size());
    assertEquals(1, controllableDurationActivities.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();
    final var durationParamActivity = durationParamActivities.iterator().next();
    final var controllableDuration = controllableDurationActivities.iterator().next();

    assertEquals(Duration.ZERO, durationParamActivity.startOffset());
    assertEquals(Duration.ZERO, growBanana.startOffset());
    assertEquals(Duration.of(5, MINUTES), controllableDuration.startOffset());
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(Duration.of(125, Duration.MINUTES), peelBanana.startOffset());
  }

  /**
   * Test that the scheduler can correctly place activities off of activities anchored to start
   * at the same time of another activity.
   */
  @Test
  void testRelativeActivityPlanZeroStartOffsetStart() {
    /*
    Start with a plan with B anchored to start of A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
     */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                Duration.ZERO,
                "DurationParameterActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                Duration.ZERO,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                new ActivityDirectiveId(1L),
                false),
            new ActivityDirectiveId(3L),
            new ActivityDirective(
                Duration.of(5, MINUTES),
                "ControllableDurationActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                new ActivityDirectiveId(2L),
                false)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    final var durationParamActivities = planByActivityType.get("DurationParameterActivity");
    final var controllableDurationActivities = planByActivityType.get("ControllableDurationActivity");

    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    assertEquals(1, durationParamActivities.size());
    assertEquals(1, controllableDurationActivities.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();
    final var durationParamActivity = durationParamActivities.iterator().next();
    final var controllableDuration = controllableDurationActivities.iterator().next();

    assertEquals(Duration.ZERO, durationParamActivity.startOffset());
    assertEquals(Duration.ZERO, growBanana.startOffset());
    assertEquals(Duration.of(5, MINUTES), controllableDuration.startOffset());
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
    assertEquals(Duration.of(65, Duration.MINUTES), peelBanana.startOffset());
  }

  /**
   * Test that the scheduler can correctly place activities off of activities anchored to start before another activity.
   */
  @Test
  void testRelativeActivityPlanNegativeStartOffsetStart() {
    /*
    Start with a plan with B anchored to A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
     */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var tenMinutes = Duration.of(10, MINUTES);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                tenMinutes,
                "DurationParameterActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                Duration.of(-5, MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                new ActivityDirectiveId(1L),
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    final var durationParamActivities = planByActivityType.get("DurationParameterActivity");

    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    assertEquals(1, durationParamActivities.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();
    final var durationParamActivity = durationParamActivities.iterator().next();

    assertEquals(tenMinutes, durationParamActivity.startOffset());
    assertEquals(Duration.of(-5, MINUTES), growBanana.startOffset());
    assertTrue(growBanana.anchoredToStart());

    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));
    assertEquals(tenMinutes, peelBanana.startOffset());
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
  }

  /**
   * Test that the scheduler can correctly place activities off of activities anchored to start after the start
   * of another activity.
   */
  @Test
  void testRelativeActivityPlanPositiveStartOffsetStart() {
    /*
    Start with a plan with B anchored to A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
     */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var tenMinutes = Duration.of(10, MINUTES);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                Duration.ZERO,
                "DurationParameterActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                tenMinutes,
                "PickBanana",
                Map.of(
                    "quantity", SerializedValue.of(1)),
                new ActivityDirectiveId(1L),
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.PickBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var pickBananas = planByActivityType.get("PickBanana");
    final var durationParamActivities = planByActivityType.get("DurationParameterActivity");

    assertEquals(1, peelBananas.size());
    assertEquals(1, pickBananas.size());
    assertEquals(1, durationParamActivities.size());
    final var peelBanana = peelBananas.iterator().next();
    final var pickBanana = pickBananas.iterator().next();
    final var durationParamActivity = durationParamActivities.iterator().next();

    assertEquals(Duration.ZERO, durationParamActivity.startOffset());

    assertEquals(tenMinutes, pickBanana.startOffset());
    assertEquals(SerializedValue.of(1), pickBanana.serializedActivity().getArguments().get("quantity"));

    assertEquals(Duration.of(15, Duration.MINUTES), peelBanana.startOffset());
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
  }

  static Stream<Arguments> caseProviderJustAfter() {
    return Stream.of(
        Arguments.of("WindowProperty.START", Duration.of(10, MINUTES).plus(1, MILLISECONDS)),
        Arguments.of("WindowProperty.END", Duration.of(70, MINUTES).plus(1, MILLISECONDS))
    );
  }

  @ParameterizedTest
  @MethodSource("caseProviderJustAfter")
  void testJustAfter(String timepoint, Duration resultingStartTime) {
    /*
    Start with a plan with B anchored to A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
     */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var tenMinutes = Duration.of(10, MINUTES);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                tenMinutes,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default function(){
          TimingConstraint.defaultPadding = Temporal.Duration.from({milliseconds:1})
            return Goal.CoexistenceGoal({
              forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
              activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
              startsAt: TimingConstraint.justAfter(%s)
            })
          }
          """.formatted(timepoint), true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");

    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();


    assertEquals(tenMinutes, growBanana.startOffset());
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    assertEquals(resultingStartTime, peelBanana.startOffset());
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
  }

  static Stream<Arguments> caseProviderJustBefore() {
    return Stream.of(
        Arguments.of("WindowProperty.START", Duration.of(10, MINUTES).minus(1, MICROSECONDS)),
        Arguments.of("WindowProperty.END", Duration.of(70, MINUTES).minus(1, MICROSECONDS))
    );
  }

  @ParameterizedTest
  @MethodSource("caseProviderJustBefore")
  void testJustBefore(String timepoint, Duration resultingStartTime) {
    /*
    Start with a plan with B anchored to A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
     */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var tenMinutes = Duration.of(10, MINUTES);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                tenMinutes,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.justBefore(%s)
          })
          """.formatted(timepoint), true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");

    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();


    assertEquals(tenMinutes, growBanana.startOffset());
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    assertEquals(resultingStartTime, peelBanana.startOffset());
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
  }

  /**
   * Test that the scheduler can correctly place activities off of activities anchored to start after the start
   * of another activity.
   */
  @Test
  void testRelativeActivityPlanPositiveEndOffsetEnd() {
    /*
    Start with a plan with B anchored to A
    Goal: for each B, place a C
    And make sure that C ends up in the right place
     */
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var tenMinutes = Duration.of(10, MINUTES);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                Duration.ZERO,
                "DurationParameterActivity",
                Map.of(
                    "duration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                tenMinutes,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                new ActivityDirectiveId(1L),
                false)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());
    assertEquals(1, goalResult.createdActivities().size());
    for (final var activity : goalResult.createdActivities()) {
      assertNotNull(activity);
    }
    for (final var activity : goalResult.satisfyingActivities()) {
      assertNotNull(activity);
    }

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var peelBananas = planByActivityType.get("PeelBanana");
    final var growBananas = planByActivityType.get("GrowBanana");
    final var durationParamActivities = planByActivityType.get("DurationParameterActivity");

    assertEquals(1, peelBananas.size());
    assertEquals(1, growBananas.size());
    assertEquals(1, durationParamActivities.size());
    final var peelBanana = peelBananas.iterator().next();
    final var growBanana = growBananas.iterator().next();
    final var durationParamActivity = durationParamActivities.iterator().next();

    assertEquals(Duration.ZERO, durationParamActivity.startOffset());

    assertEquals(Duration.of(10, MINUTES), growBanana.startOffset());
    assertFalse(growBanana.anchoredToStart());
    assertEquals(SerializedValue.of(1), growBanana.serializedActivity().getArguments().get("quantity"));

    assertEquals(Duration.of(75, Duration.MINUTES), peelBanana.startOffset());
    assertEquals(SerializedValue.of("fromStem"), peelBanana.serializedActivity().getArguments().get("peelDirection"));
  }

  /**
   * Test that the scheduler does not schedule activities based on activities which are outside plan bounds
   */
  @Test
  void testDontScheduleFromOutsidePlanBounds(){
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            // Activity Before Plan Start
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                Duration.of(-10, MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            // Activity Anchored to run after Plan End
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                Duration.of(10, MINUTES),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(2),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                false)),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
          export default () => Goal.CoexistenceGoal({
            forEach: ActivityExpression.ofType(ActivityTypes.GrowBanana),
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
            startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
          })
          """, true)),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    // The goal is satisfied, but placed no activities,
    // because all activities it could've matched on are outside the plan bounds
    assertTrue(goalResult.satisfied());
    assertEquals(0, goalResult.createdActivities().size());

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    final var growBananas = planByActivityType.get("GrowBanana");

    assertNull(planByActivityType.get("PeelBanana"));
    assertEquals(2, growBananas.size());

    assertTrue(
        growBananas.stream().anyMatch(
            $ -> Objects.equals($.startOffset(), Duration.of(-10, MINUTES))
                 && SerializedValue.of(1).equals($.serializedActivity().getArguments().get("quantity"))
        )
    );
    assertTrue(
        growBananas.stream().anyMatch(
            $ -> Objects.equals($.startOffset(), Duration.of(10, MINUTES))
                 && SerializedValue.of(2).equals($.serializedActivity().getArguments().get("quantity"))
        )
    );
  }

  /**
   * Test the option to turn off simulation in between goals.
   *
   * Goal 0 places `PeelBanana`s. `PeelBanana`s effect the `/peel` resource.
   * If the `/peel` resource is unchanged because it didn't resimulate, Goal 1 will not place any activities.
   * If the resource is resimulated, it will be different and Goal 1 will place one activity.
   *
   * Both options are tested here.
   */
  @Test
  void testOptionalSimulationAfterGoal_staleResources() {
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var configs = Map.of(
        false, 0, // don't simulate, expect 0 activities
        true, 1 // do simulate, expect 1 activity
    );
    for (final var config: configs.entrySet()) {
      final var results = runScheduler(
          BANANANATION,
          Map.of(
              new ActivityDirectiveId(1L),
              new ActivityDirective(
                  Duration.ZERO,
                  "GrowBanana",
                  Map.of(
                      "quantity", SerializedValue.of(1),
                      "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                  null,
                  true)
          ),
          List.of(
              new EdslGoal(new GoalId(0L, 0L), """
                  export default () => Goal.CoexistenceGoal({
                    forEach: Real.Resource("/fruit").greaterThan(4),
                    activityTemplate: ActivityTemplates.DownloadBanana({connection: "DSL"}),
                    startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes: 5 }))
                  })
                  """, true, config.getKey()
              ),
              new EdslGoal(new GoalId(1L, 0L), """
                  export default () => Goal.CoexistenceGoal({
                    forEach: Real.Resource("/fruit").greaterThan(5),
                    activityTemplate: ActivityTemplates.BananaNap(),
                    startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes: 5 }))
                  })
                    """, true, true)
          ),
          PLANNING_HORIZON);

      assertEquals(2, results.scheduleResults.goalResults().size());
      final var goalResult1 = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));
      final var goalResult2 = results.scheduleResults.goalResults().get(new GoalId(1L, 0L));

      assertTrue(goalResult1.satisfied());
      assertTrue(goalResult2.satisfied());
      assertEquals(1, goalResult1.createdActivities().size());
      assertEquals(config.getValue(), goalResult2.createdActivities().size());
      for (final var activity : goalResult1.createdActivities()) {
        assertNotNull(activity);
      }
    }
  }

  private static final MissionModelDescription FOO = new MissionModelDescription(
      "foo",
      Map.of(),
      Path.of(System.getenv("AERIE_ROOT"), "examples", "foo-missionmodel", "build", "libs")
  );

  /**
   * Test that the daemon task did not get dropped.
   * If the daemon was dropped, then DaemonCheckerActivity will throw a RuntimeException when simulated.
   */
  @Test
  void daemonTaskTest(){
    final PlanningHorizon PLANNING_HORIZON = new PlanningHorizon(
        TimeUtility.fromDOY("2030-001T00:00:00"),
        TimeUtility.fromDOY("2030-001T02:00:00"));
    final var results = runScheduler(
        FOO,
        List.of(
            new ActivityDirective(
                Duration.of(5, MINUTES),
                "ZeroDurationUncontrollableActivity",
                Map.of(),
                null,
                true)
        ),
        List.of(new EdslGoal(new GoalId(0L, 0L), """
      export default () => Goal.CoexistenceGoal({
        forEach: ActivityExpression.ofType(ActivityTypes.ZeroDurationUncontrollableActivity),
        activityTemplate: ActivityTemplates.DaemonCheckerActivity({
          "minutesElapsed": 10
        }),
        startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes: 5, seconds: 1 })),
        });
      """, true)), PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    assertTrue(results.scheduleResults.goalResults().get(new GoalId(0L, 0L)).satisfied());

    assertEquals(2, results.updatedPlan.size());

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    assertEquals(2, planByActivityType.size());

    final var zeroDurations = planByActivityType.get("ZeroDurationUncontrollableActivity");
    final var daemonCheckers = planByActivityType.get("DaemonCheckerActivity");
    assertEquals(1, zeroDurations.size());
    assertEquals(1, daemonCheckers.size());

    final var zeroDuration = zeroDurations.iterator().next();
    final var daemonChecker = daemonCheckers.iterator().next();

    assertEquals(Duration.of(5, MINUTES), zeroDuration.startOffset());
    assertEquals(Duration.of(10, MINUTES).plus(Duration.of(1, SECONDS)), daemonChecker.startOffset());
  }

  /**
   * This regression test makes sure that even models that declare no numeric resources generate valid typescript
   */
  @Test
  void testEmptyPlanMinimalMissionModelSimpleRecurrenceGoal() {
    runScheduler(MINIMAL_MISSION_MODEL, List.of(), List.of(new EdslGoal(new GoalId(0L, 0L), """
        export default () => Goal.ActivityRecurrenceGoal({
          activityTemplate: ActivityTemplates.SingleActivity(),
          interval: Temporal.Duration.from({ milliseconds: 24 * 60 * 60 * 1000 })
        })
        """, true)), PLANNING_HORIZON);

    // If invalid typescript is generated, an exception will be thrown.
  }

  // Tests that the scheduler can handle an initial plan with various duration annotated activities.
  @Test
  void testSchedulerAgentDurationTypeHandling() {
    runScheduler(
        BANANANATION,
        List.of(
            new ActivityDirective(
                Duration.ZERO,
                "BananaNap",
                Map.of(),
                null,
                true
            ),
            new ActivityDirective(
                Duration.SECOND,
                "DownloadBanana",
                Map.of("connection", SerializedValue.of("DietaryFiberOptic")),
                null,
                true
            )
        ),
        List.of(),
        PLANNING_HORIZON);
  }

  /**
   * Test filtering CoexistenceGoal.forEach on activity arguments.
   */
  @Test
  void testForEachWithActivityArguments() {
    final var activityDuration = Duration.of(1, Duration.HOUR);
    final var results = runScheduler(
        BANANANATION,
        Map.of(
            new ActivityDirectiveId(1L),
            new ActivityDirective(
                Duration.ZERO,
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(2),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true),
            new ActivityDirectiveId(2L),
            new ActivityDirective(
                Duration.of(2, activityDuration),
                "GrowBanana",
                Map.of(
                    "quantity", SerializedValue.of(1),
                    "growingDuration", SerializedValue.of(activityDuration.in(Duration.MICROSECONDS))),
                null,
                true)
        ),
        List.of(
            new EdslGoal(new GoalId(0L, 0L), """
                  export default () => Goal.CoexistenceGoal({
                    forEach: ActivityExpression.build(ActivityTypes.GrowBanana, {quantity: 1}),
                    activityTemplate: ActivityTemplates.PeelBanana({peelDirection: "fromStem"}),
                    startsAt: TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes: 5 }))
                  })
                  """, true, true
            )
        ),
        PLANNING_HORIZON);

    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());

    final var planByActivityType = partitionByActivityType(results.updatedPlan());
    assertEquals(2, planByActivityType.size());

    final var peels = planByActivityType.get("PeelBanana");
    assertEquals(1, peels.size());
    assertEquals(peels.iterator().next().startOffset(), Duration.of(5, MINUTES).plus(Duration.of(2, activityDuration)));
  }

  @Test
  void testListOfListParam() {
    final var results = runScheduler(FOO, List.of(), List.of(new EdslGoal(new GoalId(0L, 0L), """
        export default function myGoal() {
                          return Goal.CardinalityGoal({
                            activityTemplate: ActivityTemplates.foo({
                              x: 1,
                              y:"test2",
                              z:3,
                              vecs: [[1, 2, 3], [4, 5, 6]]
                            }),
                            specification : {occurrence: 1}
                          })
        }
          """, true)),List.of(), PLANNING_HORIZON);
    assertEquals(1, results.scheduleResults.goalResults().size());
    final var goalResult = results.scheduleResults.goalResults().get(new GoalId(0L, 0L));

    assertTrue(goalResult.satisfied());

    final var activitiesByType = partitionByActivityType(results.updatedPlan());

    final var foos = activitiesByType.get("foo");
    assertEquals(1, foos.size());
    final var insertedFoo = foos.iterator().next();
    final var vecs = insertedFoo.serializedActivity().getArguments().get("vecs").asList();
    assertTrue(vecs.isPresent());
    assertEquals(2, vecs.get().size());
  }
}
