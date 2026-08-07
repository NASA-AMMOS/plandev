package gov.nasa.jpl.aerie.e2e.procedural.constraints;

import gov.nasa.jpl.aerie.e2e.procedural.scheduling.ProceduralTestingSetup;
import gov.nasa.jpl.aerie.e2e.types.ConstraintInvocationId;
import gov.nasa.jpl.aerie.e2e.types.ConstraintResult;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("constraints")
@Tag("procedural")
public class BasicConstraintTests extends ProceduralTestingSetup {
  private ConstraintInvocationId fruitThresholdConstraintId;
  private ConstraintInvocationId noMessageConstraintId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      final int fruitThresholdConstraintJarId = gateway.uploadJarFile("build/libs/FruitThresholdConstraint.jar");
      final int noMessageConstraintJarId = gateway.uploadJarFile("build/libs/NoMessageConstraint.jar");
      // Add Constraint Procedures
      fruitThresholdConstraintId = hasura.createConstraintSpecProcedure(
          "Fruit Threshold Constraint",
          fruitThresholdConstraintJarId,
          planId
      );
      noMessageConstraintId = hasura.createConstraintSpecProcedure(
          "No Message Constraint",
          noMessageConstraintJarId,
          planId
      );

      // Disable the noMessageConstraint by default
      hasura.updatePlanConstraintSpecEnabled(noMessageConstraintId.invocationId(), false);
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteConstraint(fruitThresholdConstraintId.id());
    hasura.deleteConstraint(noMessageConstraintId.id());
  }

  /**
   * Run a spec with one procedure in it with required params but no args set
   * Should fail because one argument is provided in the template but not the other
   */
  @Test
  void executeConstraintRunWithoutArguments() throws IOException {
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);
    assertEquals(1, resp.constraintsRun().size());
    final var constraint =  resp.constraintsRun().getFirst();
    assertEquals(1, constraint.errors().size());
    assertTrue(constraint.errors().getFirst().message().contains(
        "gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException: Invalid arguments for input type "
        + "\"FruitThresholdConstraint\": "
        + "extraneous arguments: [], "
        + "unconstructable arguments: [], "
        + "missing arguments: [MissingArgument[parameterName=upperBound, schema=IntSchema[]]], "
        + "valid arguments: [ValidArgument[parameterName=lowerBound, serializedValue=NumericValue[value=5]]]"));
  }

  /**
   * Run a constraint that has one template argument and requires one other argument
   */
  @Test
  void executeConstraintRunWithArguments() throws IOException {
    final var args = Json.createObjectBuilder().add("upperBound", 10).build();
    hasura.updateConstraintArguments(fruitThresholdConstraintId.invocationId(), args);
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);
    assertTrue(resp.constraintsRun().getFirst().success());
    final var violations = resp.constraintsRun().getFirst().result().get().violations();
    assertEquals(1, violations.size());

    var violation = violations.getFirst();
    assertEquals(violation.windows(), List.of(new ConstraintResult.Interval(0, Duration.hours(48).micros())));
  }

  /**
   * Test that constraints with a violation message include said violation message.
   */
  @Test
  void messageReturnedIfPresent() throws IOException {
    // Enable the NoMessageConstraint
    hasura.updatePlanConstraintSpecEnabled(noMessageConstraintId.invocationId(), true);

    // Assign args to the constraints
    final var args = Json.createObjectBuilder().add("upperBound", 10).build();
    hasura.updateConstraintArguments(fruitThresholdConstraintId.invocationId(), args);
    hasura.updateConstraintArguments(noMessageConstraintId.invocationId(), args);

    // Get Constraint Results
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    assertEquals(2, resp.constraintsRun().size());

    final var firstConstraint = resp.constraintsRun().getFirst();
    assertEquals(fruitThresholdConstraintId.invocationId(), firstConstraint.constraintInvocationId());
    assertTrue(firstConstraint.success());
    assertEquals(1, firstConstraint.result().get().violations().size());
    final var firstConstraintViolation = firstConstraint.result().get().violations().getFirst();
    assertTrue(firstConstraintViolation.message().isPresent());
    assertEquals("Fruit count is outside of boundaries: [5, 10]", firstConstraintViolation.message().get());

    final var secondConstraint = resp.constraintsRun().getLast();
    assertEquals(noMessageConstraintId.invocationId(), secondConstraint.constraintInvocationId());
    assertTrue(secondConstraint.success());
    assertEquals(1, secondConstraint.result().get().violations().size());
    final var secondConstraintViolation = secondConstraint.result().get().violations().getFirst();
    assertTrue(secondConstraintViolation.message().isEmpty());
  }

  /**
   * Queries the procedural constraints arguments.
   */
  @Test
  void effectiveArgumentsQuery() throws IOException {
    final var effectiveArgs = hasura.getEffectiveProceduralConstraintsArgumentsBulk(
        List.of(Pair.of(fruitThresholdConstraintId.id(), Json.createObjectBuilder().add("upperBound", 10).build())));
    assertEquals(1, effectiveArgs.size());
    assertTrue(effectiveArgs.getFirst().success());
    assertTrue(effectiveArgs.getFirst().arguments().isPresent());
    assertTrue(effectiveArgs.getFirst().errors().isEmpty());

    // Check returned Arguments
    final var args = effectiveArgs.getFirst().arguments().get();
    assertEquals(2, args.size());
    assertEquals(10, args.getInt("upperBound"));
    assertEquals(5, args.getInt("lowerBound"));
  }
}
