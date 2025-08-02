package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicTests extends ProceduralSchedulingSetup {
  private int procedureJarId;
  private GoalInvocationId procedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      procedureJarId = gateway.uploadJarFile("build/libs/DumbRecurrenceGoal.jar");
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

  /**
   * Upload a procedure jar and add to spec
   */
  @Test
  void proceduralUploadWorks() throws IOException {
    final var ids = hasura.getSchedulingSpecGoalIds(specId);

    assertEquals(1, ids.size());
    assertEquals(procedureId.goalId(), ids.getFirst());
  }

  /**
   * Run a spec with one procedure in it with required params but no args set
   * Should fail scheduling run
   */
  @Test
  void executeSchedulingRunWithoutArguments() throws IOException {
    final var resp = hasura.awaitFailingScheduling(specId);
    final var message = resp.reason().getString("message");
    assertTrue(message.contains("java.lang.RuntimeException: Record missing key Component[name=quantity"));
  }

  /**
   * Run a spec with one procedure in it
   */
  @Test
  void executeSchedulingRunWithArguments() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "24:00:00")
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "30:00:00")
    ));
  }

  /**
   * Run a spec with two invocations of the same procedure in it
   */
  @Test
  void executeMultipleInvocationsOfSameProcedure() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final var secondInvocationId = hasura.insertGoalInvocation(procedureId.goalId(), specId);
    hasura.updateSchedulingSpecGoalArguments(secondInvocationId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(4, activities.size());
  }

  /**
   * Run a spec with two procedures in it
   */
  @Test
  void executeMultipleProcedures() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final var secondProcedure = hasura.createSchedulingSpecProcedure(
        "Test Scheduling Procedure 2",
        procedureJarId,
        specId,
        1);

    hasura.updateSchedulingSpecGoalArguments(secondProcedure.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(4, activities.size());
  }

  /**
   * Run a spec with one EDSL goal and one procedure
   */
  @Test
  void executeEDSLAndProcedure() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 4).build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final String recurrenceGoalDefinition =
        """
        export default function myGoal() {
          return Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: 'fromStem'}),
            interval: Temporal.Duration.from({hours:1})
        })}""";

    hasura.createSchedulingSpecGoal(
        "Recurrence Scheduling Test Goal",
        recurrenceGoalDefinition,
        specId,
        1);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(52, activities.size());
  }

  /**
   * Run a spec with one procedure and make sure the activity names are saved to the database
   */
  @Test
  void saveActivityName() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());
    assertEquals("It's a bite banana activity", activities.getFirst().name());
  }
}
