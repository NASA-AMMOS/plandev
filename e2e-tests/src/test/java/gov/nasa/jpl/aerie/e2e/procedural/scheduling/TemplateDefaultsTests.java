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

public class TemplateDefaultsTests extends ProceduralTestingSetup {
  private int procedureJarId;
  private GoalInvocationId procedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      procedureJarId = gateway.uploadJarFile("build/libs/DumbRecurrenceGoalWithTemplateDefaults.jar");
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
   * Running without argument works because of template defaults
   */
  @Test
  void executeSchedulingRunWithoutArguments() throws IOException {
    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(3, activities.size());

    assertTrue(activities.stream().anyMatch(
        it ->
            Objects.equals(it.type(), "BiteBanana") &&
            Objects.equals(it.startOffset(), "24:00:00") &&
            Objects.equals(it.arguments().getInt("biteSize"), 5)));

    assertTrue(activities.stream().anyMatch(
        it ->
            Objects.equals(it.type(), "BiteBanana") &&
            Objects.equals(it.startOffset(), "30:00:00") &&
            Objects.equals(it.arguments().getInt("biteSize"), 5)));

    assertTrue(activities.stream().anyMatch(
        it ->
            Objects.equals(it.type(), "BiteBanana") &&
            Objects.equals(it.startOffset(), "36:00:00") &&
            Objects.equals(it.arguments().getInt("biteSize"), 5)));
  }

  /**
   * Run a spec with one procedure in it and just one of the argument. The other should be provided by the template.
   */
  @Test
  void executeSchedulingRunWithOneArgument() throws IOException {
    final var args = Json.createObjectBuilder().add("biteSize", 2).build();

    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(3, activities.size());

    assertTrue(activities.stream().anyMatch(
        it ->
            Objects.equals(it.type(), "BiteBanana") &&
            Objects.equals(it.startOffset(), "24:00:00") &&
            Objects.equals(it.arguments().getInt("biteSize"), 2)));

    assertTrue(activities.stream().anyMatch(
        it ->
            Objects.equals(it.type(), "BiteBanana") &&
            Objects.equals(it.startOffset(), "30:00:00") &&
            Objects.equals(it.arguments().getInt("biteSize"), 2)));

    assertTrue(activities.stream().anyMatch(
        it ->
            Objects.equals(it.type(), "BiteBanana") &&
            Objects.equals(it.startOffset(), "36:00:00") &&
            Objects.equals(it.arguments().getInt("biteSize"), 2)));
  }

}
