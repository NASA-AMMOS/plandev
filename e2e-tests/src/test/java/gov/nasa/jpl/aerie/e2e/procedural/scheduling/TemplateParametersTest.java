package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TemplateParametersTest extends ProceduralSchedulingSetup {
  private GoalInvocationId dumbRecurrenceWithTemplateDefaultsGoalId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      final int dumbRecurrenceWithTemplateDefaultsGoalJarId = gateway.uploadJarFile(
          "build/libs/DumbRecurrenceGoalWithTemplateDefaults.jar");
      // Add Scheduling Procedure
      dumbRecurrenceWithTemplateDefaultsGoalId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure 1",
          dumbRecurrenceWithTemplateDefaultsGoalJarId,
          specId,
          0
      );
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(dumbRecurrenceWithTemplateDefaultsGoalId.goalId());
  }

  /**
   * Run a spec with one procedure in it with required params but no args set
   * Should succeed because of default arguments
   */
  @Test
  void executeSchedulingRunWithoutArguments() throws IOException {
    hasura.awaitScheduling(specId);
    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(3, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "24:00:00")
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "30:00:00")
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "36:00:00")
    ));
  }

  @Test
  void defaultArgsTotal() throws IOException {
    final var effectiveArgs = hasura.getEffectiveProceduralGoalsArgumentsBulk(
        List.of(Pair.of(dumbRecurrenceWithTemplateDefaultsGoalId.goalId(), JsonValue.EMPTY_JSON_OBJECT)));
    assertEquals(1, effectiveArgs.size());
    assertTrue(effectiveArgs.get(0).success());
    assertTrue(effectiveArgs.get(0).arguments().isPresent());
    assertTrue(effectiveArgs.get(0).errors().isEmpty());

    // Check returned Arguments
    final var args = effectiveArgs.get(0).arguments().get();
    assertEquals(2, args.size());
    assertEquals(5, args.getInt("biteSize"));
    assertEquals(3, args.getInt("quantity"));
  }

  @Test
  void defaultArgsPartial() throws IOException {
    final var effectiveArgs = hasura.getEffectiveProceduralGoalsArgumentsBulk(
        List.of(Pair.of(
            dumbRecurrenceWithTemplateDefaultsGoalId.goalId(),
            Json.createObjectBuilder().add("quantity", 4).build())));
    assertEquals(1, effectiveArgs.size());
    assertTrue(effectiveArgs.get(0).success());
    assertTrue(effectiveArgs.get(0).arguments().isPresent());
    assertTrue(effectiveArgs.get(0).errors().isEmpty());

    // Check returned Arguments
    final var args = effectiveArgs.get(0).arguments().get();
    assertEquals(2, args.size());
    assertEquals(5, args.getInt("biteSize"));
    assertEquals(4, args.getInt("quantity"));
  }

}
