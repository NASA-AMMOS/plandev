package gov.nasa.jpl.aerie.e2e.procedural.constraints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.ProceduralSchedulingSetup;
import gov.nasa.jpl.aerie.e2e.types.ConstraintInvocationId;
import gov.nasa.jpl.aerie.e2e.types.ConstraintResult;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicConstraintTests extends ProceduralSchedulingSetup {
  private ConstraintInvocationId fruitTresholdConstraintId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      final int fruitTresholdConstraintJarId = gateway.uploadJarFile("build/libs/FruitThresholdConstraint.jar");
      // Add Scheduling Procedure
      fruitTresholdConstraintId = hasura.createConstraintSpecProcedure(
          "Test Constraint Procedure 1",
          fruitTresholdConstraintJarId,
          planId
      );
    }
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteConstraint(fruitTresholdConstraintId.id());
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
    assertEquals(1, resp.constraintsRun().getFirst().errors().size());
    assertTrue(resp.constraintsRun().getFirst().errors().getFirst().message().contains("gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException: Invalid arguments for input type \"FruitThresholdConstraint\": extraneous arguments: [], unconstructable arguments: [], missing arguments: [MissingArgument[parameterName=upperBound, schema=IntSchema[]]], valid arguments: [ValidArgument[parameterName=lowerBound, serializedValue=NumericValue[value=5]]]"));
  }

  /**
   * Run a constraint that has one template argument and requires one other argument
   */
  @Test
  void executeConstraintRunWithArguments() throws IOException {
    final var args = JsonNodeFactory.instance.objectNode().put("upperBound", 10);
    hasura.updateConstraintArguments(fruitTresholdConstraintId.invocationId(), args);
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);
    assertTrue(resp.constraintsRun().getFirst().success());
    final var violations = resp.constraintsRun().getFirst().result().get().violations();
    assertEquals(1, violations.size());

    var violation = violations.getFirst();
    assertEquals(violation.windows(), List.of(new ConstraintResult.Interval(0, Duration.hours(48).micros())));
  }

  /**
   * Queries the procedural constraints arguments.
   */
  @Test
  void effectiveArgumentsQuery() throws IOException {
    final var effectiveArgs = hasura.getEffectiveProceduralConstraintsArgumentsBulk(
        List.of(Pair.of(fruitTresholdConstraintId.id(), JsonNodeFactory.instance.objectNode().put("upperBound", 10))));
    assertEquals(1, effectiveArgs.size());
    assertTrue(effectiveArgs.get(0).success());
    assertTrue(effectiveArgs.get(0).arguments().isPresent());
    assertTrue(effectiveArgs.get(0).errors().isEmpty());

    // Check returned Arguments
    final var args = effectiveArgs.get(0).arguments().get();
    assertEquals(2, args.size());
    assertEquals(10, args.get("upperBound").intValue());
    assertEquals(5, args.get("lowerBound").intValue());
  }
}
