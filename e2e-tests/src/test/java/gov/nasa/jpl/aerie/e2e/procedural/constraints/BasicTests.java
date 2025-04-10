package gov.nasa.jpl.aerie.e2e.procedural.constraints;

import gov.nasa.jpl.aerie.e2e.procedural.ProceduralSetup;
import gov.nasa.jpl.aerie.e2e.types.ConstraintActionResponse;
import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicTests extends ProceduralSetup {
  private int procedureJarId;
  private int constraintId;
  private int invocationId;
  private int simulationDatasetId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      procedureJarId = gateway.uploadJarFile("build/libs/FruitThreshold.jar");

      // Create Constraint Procedure
      constraintId = hasura.createConstraintProcedure("FruitThreshold", procedureJarId);

      // Link it to plan's constraint spec
      invocationId = hasura.createConstraintProcedureSpec(planId, constraintId);

      // Get Simulation Id
      simulationDatasetId = hasura.awaitSimulation(planId).simDatasetId();
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    // delete the constraint
    hasura.deleteConstraint(constraintId);
  }

  /**
   * Upload a procedure jar and add to spec
   */
  @Test
  void proceduralUploadWorks() throws IOException {
    final var ids = hasura.getConstraintSpec(planId);

    assertEquals(1, ids.size());
    assertEquals(constraintId, ids.getFirst());
  }

  /**
   * Run a spec with one procedure in it with required params but no args set
   * Should fail scheduling run
   */
  @Test
  void executeConstraintsRunWithoutArguments() throws IOException {
    final var resp = hasura.checkConstraints(planId, simulationDatasetId);
    final var constraintsRun = resp.constraintsRun().getFirst();

    // expect no results
    assertTrue(constraintsRun.result().isEmpty());

    // expect errors
    assertEquals(1, constraintsRun.errors().size());
    final var message = constraintsRun.errors().getFirst().message();
    assertTrue(message.contains("Record missing key Component[name=threshold,"));
  }

  /**
   * Run a spec with one procedure in it
   */
  @Test
  void executeConstraintsRunWithArguments() throws IOException {
    // add activities
    hasura.insertActivityDirective(planId, "BiteBanana", "01:00:00", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(planId, "BiteBanana", "02:00:00", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(planId, "GrowBanana", "03:00:00", Json.createObjectBuilder().add("growingDuration", 3600000000L).build());

    // Get Simulation Id
    simulationDatasetId = hasura.awaitSimulation(planId).simDatasetId();

    final var args = Json.createObjectBuilder().add("threshold", 3).build();
    hasura.updateConstraintSpecArguments(invocationId, args);

    // Verify only one constraint has been run
    final var resp = hasura.checkConstraints(planId, simulationDatasetId).constraintsRun();
    assertEquals(1, resp.size());

    // Check the Result
    final var constraintResponse = resp.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId, constraintResponse.constraintId());
    assertEquals("FruitThreshold", constraintResponse.constraintName());
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();

    //No resources or activityInstanceIds, as this is procedural and I don't believe those get filled out
    // Violation
    assertEquals(2, constraintResult.violations().size());
    var violation1 = constraintResult.violations().getFirst();
    var violation2 = constraintResult.violations().get(1);
    assertEquals(1, violation1.windows().size());
    assertEquals(1, violation2.windows().size());

    // okay from 0 -> 2h
    final var violationStart = 2 * 60 * 60 * 1000000L; // 2h in micros
    // broken from 2h -> 4h (growing lasts from 3->4)
    final var violationEnd = 4 * 60 * 60 * 1000000L; // 4h in micros
    // fine after
    final var planEnd = 48 * 60 * 60 * 1000000L; // 48h in micros

    assertEquals(0, violation1.windows().getFirst().start());
    assertEquals(violationStart, violation1.windows().getFirst().end());

    assertEquals(violationEnd, violation2.windows().getFirst().start());
    assertEquals(planEnd, violation2.windows().getFirst().end());

    // Gaps (NOTE: not sure how to fill these up)
    assertTrue(constraintResult.gaps().isEmpty());
  }

  // TODO: a constraint that has gaps?

  /**
   * Run a spec with two procedures in it
   */
  @Test
  void executeMultipleProcedures() throws IOException {
    // upload second constraint
    int secondProcedureJarId;
    int secondConstraintId;
    int secondInvocationId;
    try (final var gateway = new GatewayRequests(playwright)) {
      secondProcedureJarId = gateway.uploadJarFile("build/libs/ActivityCounter.jar");
      secondConstraintId = hasura.createConstraintProcedure("ActivityCounter", secondProcedureJarId);
      secondInvocationId = hasura.createConstraintProcedureSpec(planId, secondConstraintId);
    }

    // add activities
    hasura.insertActivityDirective(planId, "BiteBanana", "01:00:00", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(planId, "BiteBanana", "02:00:00", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(planId, "GrowBanana", "03:00:00", Json.createObjectBuilder().add("growingDuration", 3600000000L).build());

    // Get Simulation Id
    simulationDatasetId = hasura.awaitSimulation(planId).simDatasetId();

    // add arguments to both constraints
    var args = Json.createObjectBuilder().add("threshold", 3).build();
    hasura.updateConstraintSpecArguments(invocationId, args);
    args = Json.createObjectBuilder().add("quantity", 3).build();
    hasura.updateConstraintSpecArguments(secondInvocationId, args);

    // Two constraints have now been run
    final var resp = hasura.checkConstraints(planId, simulationDatasetId).constraintsRun();
    assertEquals(2, resp.size());

    // ordering not guaranteed, so sort by constraintId
    resp.sort(Comparator.comparingInt(ConstraintActionResponse.ConstraintRecord::constraintId));

    // Check the Result for the first constraint
    final var constraintResponse = resp.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId, constraintResponse.constraintId());
    assertEquals("FruitThreshold", constraintResponse.constraintName());
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();

    // Check the result for the second constaint
    final var constraintResponse2 = resp.get(1);
    assertTrue(constraintResponse2.success());
    assertEquals(secondConstraintId, constraintResponse2.constraintId());
    assertEquals("ActivityCounter", constraintResponse2.constraintName());
    assertTrue(constraintResponse2.result().isPresent());
    final var constraintResult2 = constraintResponse2.result().get();

    //No resources or activityInstanceIds, as this is procedural and I don't believe those get filled out
    // Violation on the first constraint was checked in another test. Just verify it shows up.
    assertEquals(2, constraintResult.violations().size());
    var violation1 = constraintResult.violations().getFirst();
    var violation2 = constraintResult.violations().get(1);
    assertEquals(1, violation1.windows().size());
    assertEquals(1, violation2.windows().size());

    // Verify the other result has no violations
    assertEquals(0, constraintResult2.violations().size());

    // Gaps (NOTE: not sure how to fill these up)
    assertTrue(constraintResult.gaps().isEmpty());

    // delete second constraint
    hasura.deleteConstraint(secondConstraintId);
  }

  /**
   * Run a spec with one EDSL goal and one procedure
   */
  @Test
  void executeEDSLAndProcedure() throws IOException {
    // simple constraint checking that /plant is equal to 200
    final String EDSLConstraint = "export default (): Constraint => Real.Resource(\"/fruit\").equal(Real.Value(200))";
    // Insert the Constraint
    final var insertResp = hasura.insertPlanConstraint(
            "CheckPlants",
            planId,
            EDSLConstraint,
            "");
    var secondConstraintId = insertResp.id();
    var secondInvocationId = insertResp.invocationId();

    // add activities
    hasura.insertActivityDirective(planId, "BiteBanana", "01:00:00", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(planId, "BiteBanana", "02:00:00", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(planId, "GrowBanana", "03:00:00", Json.createObjectBuilder().add("growingDuration", 3600000000L).build());

    // Get Simulation Id
    simulationDatasetId = hasura.awaitSimulation(planId).simDatasetId();

    // add arguments to first constraints
    var args = Json.createObjectBuilder().add("threshold", 3).build();
    hasura.updateConstraintSpecArguments(invocationId, args);

    // Two constraints have now been run
    final var resp = hasura.checkConstraints(planId, simulationDatasetId).constraintsRun();
    assertEquals(2, resp.size());

    // ordering not guaranteed, so sort by constraintId
    resp.sort(Comparator.comparingInt(ConstraintActionResponse.ConstraintRecord::constraintId));

    // Check the Result for the first constraint
    final var constraintResponse = resp.getFirst();
    assertTrue(constraintResponse.success());
    assertEquals(constraintId, constraintResponse.constraintId());
    assertEquals("FruitThreshold", constraintResponse.constraintName());
    assertTrue(constraintResponse.result().isPresent());
    final var constraintResult = constraintResponse.result().get();

    // Check the result for the second constaint
    final var constraintResponse2 = resp.get(1);
    assertTrue(constraintResponse2.success());
    assertEquals(secondConstraintId, constraintResponse2.constraintId());
    assertEquals("CheckPlants", constraintResponse2.constraintName());
    assertTrue(constraintResponse2.result().isPresent());
    final var constraintResult2 = constraintResponse2.result().get();

    //No resources or activityInstanceIds, as this is procedural and I don't believe those get filled out
    // Violation on the first constraint was checked in another test. Just verify it shows up.
    assertEquals(2, constraintResult.violations().size());

    // Verify the other result has no violations
    assertEquals(0, constraintResult2.violations().size());

    // Gaps (NOTE: not sure how to fill these up)
    assertTrue(constraintResult.gaps().isEmpty());

    // delete second constraint
    hasura.deleteConstraint(secondConstraintId);
  }
}
