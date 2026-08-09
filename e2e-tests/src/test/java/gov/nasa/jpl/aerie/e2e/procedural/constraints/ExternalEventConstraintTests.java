package gov.nasa.jpl.aerie.e2e.procedural.constraints;

import gov.nasa.jpl.aerie.e2e.procedural.scheduling.ProceduralTestingSetup;
import gov.nasa.jpl.aerie.e2e.types.ConstraintInvocationId;
import gov.nasa.jpl.aerie.e2e.types.ConstraintResult;
import gov.nasa.jpl.aerie.e2e.utils.ExternalEventUtils;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Named.named;

public class ExternalEventConstraintTests extends ProceduralTestingSetup {
  private ConstraintInvocationId procedureId;
  private final static String NULL_VALUE = "NULL";

  private ExternalEventUtils externalEventUtils;
  private final String eventType = "constraintsEvent";
  private final String sourceKey = "constraintsSourceKey";
  private final String derivationGroup = "constraintsDerivationGroup";

  @BeforeAll
  void localBeforeAll() throws IOException {
    final String sourceType = "constraintsSourceType";
    externalEventUtils = new ExternalEventUtils(playwright, hasura, sourceType, sourceKey, eventType, derivationGroup);
  }

  @AfterAll
  void localAfterAll() throws IOException {
    externalEventUtils.close();
  }

  @BeforeEach
  void localBeforeEach() throws IOException {
    // make associations between plans and derivation groups
    hasura.insertPlanDerivationGroupAssociation(planId, derivationGroup);
    hasura.insertPlanDerivationGroupAssociation(planId, externalEventUtils.alternateDerivationGroup());
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteConstraint(procedureId.id());

    hasura.deletePlanDerivationGroupAssociation(planId, derivationGroup);
    hasura.deletePlanDerivationGroupAssociation(planId, externalEventUtils.alternateDerivationGroup());
  }

  void uploadConstraint(String constraintName, int planId) throws IOException{
    try (final var gateway = new GatewayRequests(playwright)) {
      final int constraintJarId = gateway.uploadJarFile("build/libs/" + constraintName + ".jar");
      // Add Scheduling Procedure
      procedureId = hasura.createConstraintSpecProcedure(
          constraintName + ".jar",
          constraintJarId,
          planId
      );
    }
  }

  void uploadConstraint(String constraintName) throws IOException{
    uploadConstraint(constraintName, planId);
  }

  // check that plan.events() functions correctly if nothing is attached
  @Test
  void verifyAbsenceOfExternalEvents() throws IOException{
    // in this case, create a plan with no derivation group associations
    var newPlanId = hasura.createPlan(
        modelId,
        "No Derivation Groups".formatted(this.getClass().getSimpleName()),
        "48:00:00",
        planStartTimestamp);

    // upload the constraint
    uploadConstraint("ExternalEventAbsenceConstraint", newPlanId);

    // run it
    hasura.awaitSimulation(newPlanId);
    final var resp = hasura.checkConstraints(newPlanId);

    // check that there are no violations, and no errors
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(0, resp.constraintsRun().getFirst().result().get().violations().size());

    // manually delete the plan
    hasura.deletePlan(newPlanId);
  }

  // verify the presence and location of events of a given type
  @Test
  void verifyPresenceOfExternalEventByEventType() throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventPresenceConstraint");

    // update constraint arguments
    final var args = Json.createObjectBuilder()
                         .add("eventType", eventType)
                         .add("derivationGroup", NULL_VALUE)
                         .add("sourceKey", NULL_VALUE)
                         .build();
    hasura.updateConstraintArguments(procedureId.invocationId(), args);

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that external events of this type are present (expect one violation per event, so 4 total)
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(4, resp.constraintsRun().getFirst().result().get().violations().size());

    // check the windows of those violations, ensuring they line up with the events
    var violations = resp.constraintsRun().getFirst().result().get().violations();

    // violation 1: single window, for event from 01:00:00 to 02:00:00
    var firstViolation = violations.getFirst().windows();
    assertEquals(1, firstViolation.size());
    assertEquals(
        new ConstraintResult.Interval(3600000000L, 7200000000L),
        firstViolation.getFirst()
    );
    // violation 2: single window, for event from 03:00:00 to 04:00:00
    var secondViolation = violations.get(1).windows();
    assertEquals(1, secondViolation.size());
    assertEquals(
        new ConstraintResult.Interval(10800000000L, 14400000000L),
        secondViolation.getFirst()
    );
    // violation 3: single window, for event from 05:00:00 to 06:00:00
    var thirdViolation = violations.get(2).windows();
    assertEquals(1, thirdViolation.size());
    assertEquals(
        new ConstraintResult.Interval(18000000000L, 21600000000L),
        thirdViolation.getFirst()
    );
    // violation 4: single window, for event from 25:00:00 to 26:00:00
    var fourthViolation = violations.get(3).windows();
    assertEquals(1, fourthViolation.size());
    assertEquals(
        new ConstraintResult.Interval(90000000000L, 93600000000L),
        fourthViolation.getFirst()
    );
  }

  // verify the presence and location of events from a given derivation group
  @Test
  void verifyPresenceOfExternalEventByDerivationGroup() throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventPresenceConstraint");

    // update constraint arguments
    final var args = Json.createObjectBuilder()
                         .add("derivationGroup", derivationGroup)
                         .add("eventType", NULL_VALUE)
                         .add("sourceKey", NULL_VALUE)
                         .build();
    hasura.updateConstraintArguments(procedureId.invocationId(), args);

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that external events of this type are present (expect one violation per event, so 3 total)
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(3, resp.constraintsRun().getFirst().result().get().violations().size());

    // check the windows of those violations, ensuring they line up with the events
    var violations = resp.constraintsRun().getFirst().result().get().violations();

    // violation 1: single window, for event from 01:00:00 to 02:00:00
    var firstViolation = violations.getFirst().windows();
    assertEquals(1, firstViolation.size());
    assertEquals(
        new ConstraintResult.Interval(3600000000L, 7200000000L),
        firstViolation.getFirst()
    );
    // violation 2: single window, for event from 03:00:00 to 04:00:00
    var secondViolation = violations.get(1).windows();
    assertEquals(1, secondViolation.size());
    assertEquals(
        new ConstraintResult.Interval(10800000000L, 14400000000L),
        secondViolation.getFirst()
    );
    // violation 3: single window, for event from 05:00:00 to 06:00:00
    var thirdViolation = violations.get(2).windows();
    assertEquals(1, thirdViolation.size());
    assertEquals(
        new ConstraintResult.Interval(18000000000L, 21600000000L),
        thirdViolation.getFirst()
    );
  }

  // verify the presence and location of events from a given source
  @Test
  void verifyPresenceOfExternalEventBySource() throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventPresenceConstraint");

    // update constraint arguments
    final var args = Json.createObjectBuilder()
                         .add("sourceKey", sourceKey)
                         .add("derivationGroup", derivationGroup)
                         .add("eventType", NULL_VALUE)
                         .build();
    hasura.updateConstraintArguments(procedureId.invocationId(), args);

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that external events of this type are present (expect one violation per event, so 3 total)
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(3, resp.constraintsRun().getFirst().result().get().violations().size());

    // check the windows of those violations, ensuring they line up with the events
    var violations = resp.constraintsRun().getFirst().result().get().violations();

    // violation 1: single window, for event from 01:00:00 to 02:00:00
    var firstViolation = violations.getFirst().windows();
    assertEquals(1, firstViolation.size());
    assertEquals(
        new ConstraintResult.Interval(3600000000L, 7200000000L),
        firstViolation.getFirst()
    );
    // violation 2: single window, for event from 03:00:00 to 04:00:00
    var secondViolation = violations.get(1).windows();
    assertEquals(1, secondViolation.size());
    assertEquals(
        new ConstraintResult.Interval(10800000000L, 14400000000L),
        secondViolation.getFirst()
    );
    // violation 3: single window, for event from 05:00:00 to 06:00:00
    var thirdViolation = violations.get(2).windows();
    assertEquals(1, thirdViolation.size());
    assertEquals(
        new ConstraintResult.Interval(18000000000L, 21600000000L),
        thirdViolation.getFirst()
    );
  }

  private static Stream<Arguments> nonexistentEventCategoryArgs() {
    var nonexistentTestType = Json.createObjectBuilder()
                                  .add("eventType", "NonexistentTestType")
                                  .add("derivationGroup", NULL_VALUE)
                                  .add("sourceKey", NULL_VALUE)
                                  .build();
    var nonexistentDerivationGroup = Json.createObjectBuilder()
                                         .add("derivationGroup", "NonexistentDerivationGroup")
                                         .add("eventType", NULL_VALUE)
                                         .add("sourceKey", NULL_VALUE)
                                         .build();
    var nonexistentSource = Json.createObjectBuilder()
                                .add("derivationGroup", "NonexistentDerivationGroup")
                                .add("sourceKey", "NonexistentSourceKey")
                                .add("eventType", NULL_VALUE)
                                .build();

    return Stream.of(
      Arguments.arguments(named("NonexistentTestType", nonexistentTestType)),
      Arguments.arguments(named("NonexistentDerivationGroup", nonexistentDerivationGroup)),
      Arguments.arguments(named("NonexistentSource", nonexistentSource))
    );
  }

  // verify the presence and location of events from a nonexistent ee type
  @ParameterizedTest
  @MethodSource("nonexistentEventCategoryArgs")
  void verifyAbsenceOfExternalEventByNonexistentEventType(JsonObject constraintArgs) throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventPresenceConstraint");

    // update constraint arguments
    hasura.updateConstraintArguments(procedureId.invocationId(), constraintArgs);

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that no external events of this type are present
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(0, resp.constraintsRun().getFirst().result().get().violations().size());
  }

  // verify the presence and location of events under a general filter on ee type, source, and derivation group
  @Test
  void verifyPresenceOfExternalEventByGeneralFilter() throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventPresenceConstraint");


    // update constraint arguments
    final var args = Json.createObjectBuilder()
                         .add("eventType", externalEventUtils.alternateEventType())
                         .add("derivationGroup", externalEventUtils.alternateDerivationGroup())
                         .add("sourceKey", externalEventUtils.alternateSourceKey())
                         .build();
    hasura.updateConstraintArguments(procedureId.invocationId(), args);

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that external events of this type are present (expect one violation per event, so 2 total)
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(2, resp.constraintsRun().getFirst().result().get().violations().size());

    // check the windows of those violations, ensuring they line up with the events
    var violations = resp.constraintsRun().getFirst().result().get().violations();

    // violation 1: single window, for event from 27:00:00 to 38:00:00
    var firstViolation = violations.getFirst().windows();
    assertEquals(1, firstViolation.size());
    assertEquals(
        new ConstraintResult.Interval(97200000000L, 100800000000L),
        firstViolation.getFirst()
    );
    // violation 2: single window, for event from 29:00:00 to 30:00:00
    var secondViolation = violations.get(1).windows();
    assertEquals(1, secondViolation.size());
    assertEquals(
        new ConstraintResult.Interval(104400000000L, 108000000000L),
        secondViolation.getFirst()
    );
  }

  // check external events have given attribute values, and flag their locations
  @Test
  void verifyPropertiesOfExternalEvent() throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventAttributeConstraint");

    // update constraint arguments
    final var args = Json.createObjectBuilder()
                         .add("eventType", eventType)
                         .add("codeValue", "B")
                         .build();
    hasura.updateConstraintArguments(procedureId.invocationId(), args);

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that external events of this type are present (expect one violation per event, so 2 total)
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(2, resp.constraintsRun().getFirst().result().get().violations().size());

    // check the windows of those violations, ensuring they line up with the events
    var violations = resp.constraintsRun().getFirst().result().get().violations();

    // violation 1: single window, for event from 05:00:00 to 06:00:00
    var firstViolation = violations.getFirst().windows();
    assertEquals(1, firstViolation.size());
    assertEquals(
        new ConstraintResult.Interval(18000000000L, 21600000000L),
        firstViolation.getFirst()
    );
    // violation 2: single window, for event from 25:00:00 to 26:00:00
    var secondViolation = violations.get(1).windows();
    assertEquals(1, secondViolation.size());
    assertEquals(
        new ConstraintResult.Interval(90000000000L, 93600000000L),
        secondViolation.getFirst()
    );
  }

  // check external event overlaps with activity
  @Test
  void testExternalEventActivityOverlap() throws IOException {
    // upload the constraint
    uploadConstraint("ExternalEventActivityOverlapConstraint");

    // update constraint arguments
    final var args = Json.createObjectBuilder().add("eventType", eventType).build();
    hasura.updateConstraintArguments(procedureId.invocationId(), args);


    // add hour long activities
    hasura.insertActivityDirective(
        planId, "BananaNap", "0h", Json.createObjectBuilder().build());
    hasura.insertActivityDirective(
        planId, "BananaNap", "4h30m", Json.createObjectBuilder().build());

    // run it
    hasura.awaitSimulation(planId);
    final var resp = hasura.checkConstraints(planId);

    // checks that external events of this type are present (expect one violation per event, so only 1)
    assertEquals(1, resp.constraintsRun().size());
    assertEquals(0, resp.constraintsRun().getFirst().errors().size());
    assertEquals(1, resp.constraintsRun().getFirst().result().get().violations().size());

    // check the windows of those violations, ensuring they line up with the events
    var violations = resp.constraintsRun().getFirst().result().get().violations();

    // violation 1: single window, for event from 05:00:00, but cut off at 05:30:00
    var firstViolation = violations.getFirst().windows();
    assertEquals(1, firstViolation.size());
    assertEquals(
        new ConstraintResult.Interval(18000000000L, 19800000000L),
        firstViolation.getFirst()
    );
  }
}
