package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeletionTests extends ProceduralSchedulingSetup {
  private GoalInvocationId procedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ActivityDeletionGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(procedureId.goalId());
  }

  @Test
  void createsThreeActivities() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", -1)
        .add("anchorStrategy", "Error")
        .add("rollback", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(3, activities.size());

    final AtomicReference<Integer> id1 = new AtomicReference<>();
    final AtomicReference<Integer> id2 = new AtomicReference<>();
    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), null);
          if (result) id1.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), id1.get());
          if (result) id2.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), id2.get())
    ));
  }

  @Test
  void deletesLast() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", 2)
        .add("anchorStrategy", "Error")
        .add("rollback", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());

    final AtomicReference<Integer> id1 = new AtomicReference<>();
    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), null);
          if (result) id1.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), id1.get())
    ));
  }

  @Test
  void deletesMiddleCascade() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", 1)
        .add("anchorStrategy", "Cascade")
        .add("rollback", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(1, activities.size());

    assertTrue(activities.stream().anyMatch(
        it ->  Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), null)
    ));
  }

  @Test
  void deletesMiddleAnchorToParent() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", 1)
        .add("anchorStrategy", "PreserveTree")
        .add("rollback", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());

    final AtomicReference<Integer> id1 = new AtomicReference<>();
    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), null);
          if (result) id1.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it ->  Objects.equals(it.type(), "BiteBanana")
               && Objects.equals(it.anchorId(), id1.get())
               && Objects.equals(it.startOffset(), "02:00:00")
    ));
  }

  @Test
  void deletesFirstCascade() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", 0)
        .add("anchorStrategy", "Cascade")
        .add("rollback", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(0, activities.size());
  }

  @Test
  void deletesFirstReAnchorToPlan() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", 0)
        .add("anchorStrategy", "PreserveTree")
        .add("rollback", false)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());

    final AtomicReference<Integer> id2 = new AtomicReference<>();
    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana")
                             && Objects.equals(it.anchorId(), null)
                             && Objects.equals(it.startOffset(), "02:00:00");
          if (result) id2.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), id2.get())
    ));
  }

  @Test
  void anchorResetOnRollback() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("whichToDelete", 1)
        .add("anchorStrategy", "PreserveTree")
        .add("rollback", true)
        .build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(3, activities.size());

    final AtomicReference<Integer> id1 = new AtomicReference<>();
    final AtomicReference<Integer> id2 = new AtomicReference<>();
    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), null);
          if (result) id1.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it -> {
          final var result = Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), id1.get());
          if (result) id2.set(it.id());
          return result;
        }
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.anchorId(), id2.get())
    ));
  }
}
