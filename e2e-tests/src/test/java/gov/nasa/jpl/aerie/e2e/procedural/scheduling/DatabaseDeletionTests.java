package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DatabaseDeletionTests extends ProceduralSchedulingSetup {
  private GoalInvocationId procedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/DeleteBiteBananasGoal.jar");
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
  void deletesDirectiveAlreadyInDatabase() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("anchorStrategy", "PreserveTree")
        .build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT
    );
    hasura.updatePlanRevisionSchedulingSpec(planId);

    var plan = hasura.getPlan(planId);
    assertEquals(1, plan.activityDirectives().size());

    hasura.awaitScheduling(specId);

    plan = hasura.getPlan(planId);
    assertEquals(0, plan.activityDirectives().size());
  }

  @Test
  void deletesDirectiveInDatabaseWithAnchor() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("anchorStrategy", "PreserveTree")
        .build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final var bite = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT
    );

    final var grow = hasura.insertActivityDirective(
        planId,
        "GrowBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT,
        Json.createObjectBuilder().add("anchor_id", bite)
    );
    hasura.updatePlanRevisionSchedulingSpec(planId);

    var plan = hasura.getPlan(planId);
    var activities = plan.activityDirectives();
    assertEquals(2, activities.size());
    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "GrowBanana")
              && Objects.equals(it.anchorId(), bite)
              && Objects.equals(it.startOffset(), "01:00:00")
    ));

    hasura.awaitScheduling(specId);

    plan = hasura.getPlan(planId);

    activities = plan.activityDirectives();
    assertEquals(1, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "GrowBanana")
        && Objects.equals(it.anchorId(), null)
        && Objects.equals(it.startOffset(), "02:00:00")
    ));
  }

  @Test
  void deletesDirectiveInDatabaseInMiddleOfChain() throws IOException {

    // Creates 5 activities, deletes "Bite".
    // grow1 <- bite
    // bite <- grow (id not assigned to a variable)
    // bite <- grow2
    // grow2 <- grow3

    // Bite has two children, a grandchild, and a parent.

    final var args = Json
        .createObjectBuilder()
        .add("anchorStrategy", "PreserveTree")
        .build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final var grow1 = hasura.insertActivityDirective(
        planId,
        "GrowBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT
    );

    final var bite = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT,
        Json.createObjectBuilder().add("anchor_id", grow1)
    );

    int grow2 = -1;
    for (int i = 0; i < 2; i++) {
      grow2 = hasura.insertActivityDirective(
          planId,
          "GrowBanana",
          i + "h",
          JsonValue.EMPTY_JSON_OBJECT,
          Json.createObjectBuilder().add("anchor_id", bite)
      );
    }

    final var grow3 = hasura.insertActivityDirective(
        planId,
        "GrowBanana",
        "0h",
        JsonValue.EMPTY_JSON_OBJECT,
        Json.createObjectBuilder().add("anchor_id", grow2)
    );
    hasura.updatePlanRevisionSchedulingSpec(planId);

    var plan = hasura.getPlan(planId);
    var activities = plan.activityDirectives();
    assertEquals(5, activities.size());

    hasura.awaitScheduling(specId);

    plan = hasura.getPlan(planId);

    activities = plan.activityDirectives();
    assertEquals(4, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "GrowBanana")
              && Objects.equals(it.id(), grow1)
              && Objects.equals(it.anchorId(), null)
    ));
    final int finalGrow2 = grow2;
    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "GrowBanana")
              && Objects.equals(it.id(), finalGrow2)
              && Objects.equals(it.anchorId(), grow1)
              && Objects.equals(it.startOffset(), "02:00:00")
    ));
    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "GrowBanana")
              && Objects.equals(it.anchorId(), grow1)
              && Objects.equals(it.startOffset(), "01:00:00")
    ));
    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "GrowBanana")
              && Objects.equals(it.id(), grow3)
              && Objects.equals(it.anchorId(), finalGrow2)
              && Objects.equals(it.startOffset(), "00:00:00")
    ));
  }

  @Test
  void deleteCascadeInDatabase() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("anchorStrategy", "Cascade")
        .build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final var bite = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT
    );

    final var grow = hasura.insertActivityDirective(
        planId,
        "GrowBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT,
        Json.createObjectBuilder().add("anchor_id", bite)
    );
    hasura.updatePlanRevisionSchedulingSpec(planId);


    var plan = hasura.getPlan(planId);
    assertEquals(2, plan.activityDirectives().size());

    hasura.awaitScheduling(specId);

    plan = hasura.getPlan(planId);

    assertEquals(0, plan.activityDirectives().size());
  }

  @Test
  void deleteErrorInDatabase() throws IOException {
    final var args = Json
        .createObjectBuilder()
        .add("anchorStrategy", "Error")
        .build();
    hasura.updateSchedulingSpecGoalArguments(procedureId.invocationId(), args);

    final var bite = hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT
    );

    final var grow = hasura.insertActivityDirective(
        planId,
        "GrowBanana",
        "1h",
        JsonValue.EMPTY_JSON_OBJECT,
        Json.createObjectBuilder().add("anchor_id", bite)
    );
    hasura.updatePlanRevisionSchedulingSpec(planId);


    var plan = hasura.getPlan(planId);
    assertEquals(2, plan.activityDirectives().size());

    assertThrows(AssertionFailedError.class, () -> hasura.awaitScheduling(specId));

    plan = hasura.getPlan(planId);

    assertEquals(2, plan.activityDirectives().size());
  }
}
