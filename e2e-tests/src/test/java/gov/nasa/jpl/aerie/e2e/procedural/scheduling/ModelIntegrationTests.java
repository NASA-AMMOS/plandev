package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModelIntegrationTests extends ProceduralSchedulingSetup {
  private int procedureJarId;
  private GoalInvocationId procedureId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      procedureJarId = gateway.uploadJarFile("build/libs/ModelIntegrationGoal.jar");
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
  void testModelIntegration() throws IOException {
    hasura.insertActivityDirective(
        planId,
        "ChangeProducer",
        "1h",
        Json.createObjectBuilder().add("producer", Json.createValue("p")).build()
    );
    hasura.updatePlanRevisionSchedulingSpec(planId);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());
  }
}
