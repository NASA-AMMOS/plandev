package gov.nasa.ammos.plandev.e2e.procedural.scheduling;

import gov.nasa.ammos.plandev.e2e.ExternalDatasetsTest;
import gov.nasa.ammos.plandev.e2e.types.GoalInvocationId;
import gov.nasa.ammos.plandev.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExternalProfilesTests extends ProceduralTestingSetup {
  private GoalInvocationId procedureId;
  private int datasetId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      int procedureJarId = gateway.uploadJarFile("build/libs/ExternalProfileGoal.jar");
      // Add Scheduling Procedure
      procedureId = hasura.createSchedulingSpecProcedure(
          "Test Scheduling Procedure",
          procedureJarId,
          specId,
          0
      );

      datasetId = hasura.insertExternalDataset(
          planId,
          "2023-001T01:00:00.000",
          List.of(ExternalDatasetsTest.myBooleanProfile)
      );
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(procedureId.goalId());
    hasura.deleteExternalDataset(planId, datasetId);
  }

  @Test
  void testQueryExternalProfiles() throws IOException {
    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(1, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "03:00:00")
    ));
  }
}
