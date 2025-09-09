package gov.nasa.jpl.aerie.e2e.procedural.constraints;

import gov.nasa.jpl.aerie.e2e.procedural.ProceduralSetup;
import gov.nasa.jpl.aerie.e2e.types.ConstraintInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModelIntegrationConstraintTests extends ProceduralSetup {
  private int constraintJarId;
  private ConstraintInvocationId constraintId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      constraintJarId = gateway.uploadJarFile("build/libs/ModelIntegrationConstraint.jar");
      // Add Constraint Procedure
      constraintId = hasura.insertPlanConstraintJar(
          "Test Model Integration Constraint",
          planId,
          constraintJarId
      );
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteConstraint(constraintId.id());
  }

  @Test
  void testModelIntegrationConstraint() throws IOException {
    // Add an activity that will cause fruit to go below 2.0
    hasura.insertActivityDirective(
        planId,
        "BiteBanana",
        "1h",
        Json.createObjectBuilder().add("biteSize", Json.createValue(4)).build()
    );

    // Simulate the plan
    hasura.awaitSimulation(planId);

    // Run constraints and check that our constraint can access the mission model
    final var results = hasura.checkConstraints(planId);
    final var run = results.constraintsRun().getFirst();

    assertEquals("Test Model Integration Constraint", run.constraintName());

    // The constraint should run without ClassCastException and detect violations
    assertTrue(run.success());
    assertEquals(1, run.result().get().violations().size());

    assertEquals(Duration.hours(1), Duration.microseconds(run.result().get().violations().getFirst().windows().getFirst().start()));
  }
}
