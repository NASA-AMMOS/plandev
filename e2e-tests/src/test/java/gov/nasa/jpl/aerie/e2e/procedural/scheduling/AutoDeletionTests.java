package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoDeletionTests extends ProceduralTestingSetup {
  private GoalInvocationId edslId;
  private GoalInvocationId procedureId;
  private GoalInvocationId cascadeProcedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      final String coexGoalDefinition =
          """
          export default function myGoal() {
            return Goal.CoexistenceGoal({
              forEach: ActivityExpression.ofType(ActivityTypes.BiteBanana),
              activityTemplate: ActivityTemplates.GrowBanana({quantity: 1, growingDuration: Temporal.Duration.from({minutes:1})}),
              startsAt:TimingConstraint.singleton(WindowProperty.START).plus(Temporal.Duration.from({ minutes : 5}))
            })
          }""";

      /*
      Schedule logic:
      1. Use an eDSL coexistence goal that creates a GrowBanana for every BiteBanana
         This will do nothing on the first run, and may or may not do anything depending on
         the 2nd goal's auto-deletion behavior being tested.
      2. Use a procedural goal that always places a single BiteBanana, with auto-deletion.
         Takes a `deleteAtBeginning` argument to set whether the auto-deletion happens at
         the beginning of the run (before goal 1) or just before (in between 1 and 2).
       */

      edslId = hasura.createSchedulingSpecGoal(
          "Coexistence Scheduling Test Goal",
          coexGoalDefinition,
          "",
          specId,
          0,
          false
      );

      final int procedureJarId = gateway.uploadJarFile("build/libs/ActivityAutoDeletionGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          1,
          false
      );

      // Upload and by default disable the cascade test procedure
      final int cascadeJarProcedureId = gateway.uploadJarFile("build/libs/AnchorCascadeDeleteGoal.jar");
      cascadeProcedureId = hasura.createSchedulingSpecProcedure(
          "Anchor Deletion Cascade Mode Test Procedure",
          cascadeJarProcedureId,
          specId,
          2,
          true
      );
      hasura.updateSchedulingSpecEnabled(cascadeProcedureId.invocationId(), false);
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(cascadeProcedureId.goalId());
    hasura.deleteSchedulingGoal(procedureId.goalId());
    hasura.deleteSchedulingGoal(edslId.goalId());
  }

  // Just checking that the basic goal works as expected on a single run.
  // This doesn't check any interesting auto-deletion stuff.
  @Test
  void createsOneActivityIfRunOnce() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("deleteAtBeginning", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(1, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana")
    ));
  }

  // Test that if the auto-deletion is set to "just before", goal 1 sees the
  // activity created by goal 2 in the previous run, and creates a corresponding activity.
  // Scheduling is run several extra times to make sure it has achieved steady-state
  // after the first two runs.
  @Test
  void createsTwoActivitiesSteadyState_JustBefore() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("deleteAtBeginning", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    var oldBiteId = hasura.getPlan(planId).activityDirectives().getFirst().id();

    var oldGrowId = -1;

    for (int i = 0; i < 3; i++) {
      hasura.updatePlanRevisionSchedulingSpec(planId);
      hasura.awaitScheduling(specId);

      final var plan = hasura.getPlan(planId);
      final var activities = plan.activityDirectives();

      final AtomicInteger newBiteId = new AtomicInteger();
      final AtomicInteger newGrowId = new AtomicInteger();

      assertEquals(2, activities.size());

      assertTrue(activities.stream().anyMatch(
          it -> {
            final var isBite = Objects.equals(it.type(), "BiteBanana");
            if (isBite) {
              newBiteId.set(it.id());
            }
            return isBite;
          }
      ));

      assertTrue(activities.stream().anyMatch(
          it -> {
            final var isGrow = Objects.equals(it.type(), "GrowBanana");
            if (isGrow) {
              newGrowId.set(it.id());
            }
            return isGrow;
          }
      ));

      // Makes sure the bite banana is a new activity every time.
      assertNotEquals(oldBiteId, newBiteId.get());

      if (oldGrowId != -1) {
        assertEquals(oldGrowId, newGrowId.get());
      } else {
        oldGrowId = newGrowId.get();
      }

      oldBiteId = newBiteId.get();
    }
  }

  // Test that if the auto-deletion is set to "at beginning", goal 1 does not
  // see the activity created by goal 2 in the previous run and does not create
  // an activity. Goal 2 should still auto-delete and replace with a single activity.
  // Scheduling is run several extra times to make sure it has achieved steady state
  // after the first run.
  @Test
  void createsOneActivitySteadyState_AtBeginning() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("deleteAtBeginning", true)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    for (int i = 0; i < 3; i++) {
      hasura.updatePlanRevisionSchedulingSpec(planId);
      hasura.awaitScheduling(specId);

      final var plan = hasura.getPlan(planId);
      final var activities = plan.activityDirectives();

      assertEquals(1, activities.size());

      assertTrue(activities.stream().anyMatch(
          it -> Objects.equals(it.type(), "BiteBanana")
      ));
    }
  }

  @Test
  void schedulerDeletesCascade() throws IOException {
    // Enable the cascade test, disable the other two
    hasura.updateSchedulingSpecEnabled(cascadeProcedureId.invocationId(), true);
    hasura.updateSchedulingSpecEnabled(procedureId.invocationId(), false);
    hasura.updateSchedulingSpecEnabled(edslId.invocationId(), false);

    // Run Scheduling
    hasura.awaitScheduling(specId);

    // Get a record of the plan post-scheduling
    final var originalActivities = hasura.getPlan(planId).activityDirectives();

    // Run Scheduling again. This will trigger the goal's autodeletion behavior
    hasura.updatePlanRevisionSchedulingSpec(planId);
    hasura.awaitScheduling(specId);

    // Get a new copy of the plan
    final var updatedActivities = hasura.getPlan(planId).activityDirectives();

    // Sort the activities on their bite size
    originalActivities.sort(Comparator.comparingInt(a -> a.arguments().getInt("biteSize")));
    updatedActivities.sort(Comparator.comparingInt(a -> a.arguments().getInt("biteSize")));

    // Prove that the activities were deleted and remade during scheduling
    assertEquals(originalActivities.size(), updatedActivities.size());
    for(int i = 0; i < originalActivities.size(); ++i) {
      final var originalAct = originalActivities.get(i);
      final var updatedAct = updatedActivities.get(i);

      // The Activities should be identical, except for their ids, which should differ
      assertNotEquals(originalAct.id(), updatedAct.id());

      if(originalAct.anchorId() == null) {
        assertNull(updatedAct.anchorId());
      } else {
        assertNotEquals(originalAct.anchorId(), updatedAct.anchorId());
      }

      assertEquals(originalAct.planId(), updatedAct.planId());
      assertEquals(originalAct.type(), updatedAct.type());
      assertEquals(originalAct.startOffset(), updatedAct.startOffset());
      assertEquals(originalAct.arguments(), updatedAct.arguments());
      assertEquals(originalAct.name(), updatedAct.name());
      assertEquals(originalAct.anchoredToStart(), updatedAct.anchoredToStart());
    }
  }
}
