package gov.nasa.jpl.aerie.e2e.procedural.scheduling;

import gov.nasa.jpl.aerie.e2e.types.GoalInvocationId;
import gov.nasa.jpl.aerie.e2e.types.Plan;
import gov.nasa.jpl.aerie.e2e.types.SimulationDataset;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicSchedulingTests extends ProceduralTestingSetup {
  private int dumbRecurrenceGoalJarId;
  private int decompositionGoalJarId;
  private GoalInvocationId dumbRecurrenceGoalId;

  @BeforeEach
  void localBeforeEach() throws IOException {
    // Upload the Jars
    try (final var gateway = new GatewayRequests(playwright)) {
      dumbRecurrenceGoalJarId = gateway.uploadJarFile("build/libs/DumbRecurrenceGoal.jar");
      decompositionGoalJarId = gateway.uploadJarFile("build/libs/DecompositionSchedulingGoal.jar");
    }

    // Add Scheduling Procedure
    dumbRecurrenceGoalId = hasura.createSchedulingSpecProcedure(
        "Test Scheduling Procedure 1",
        dumbRecurrenceGoalJarId,
        specId,
        0
    );
  }

  @AfterEach
  void localAfterEach() throws IOException {
    hasura.deleteSchedulingGoal(dumbRecurrenceGoalId.goalId());
  }

  /**
   * Upload a procedure jar and add to spec
   */
  @Test
  void proceduralUploadWorks() throws IOException {
    final var ids = hasura.getSchedulingSpecGoalIds(specId);

    assertEquals(1, ids.size());
    assertEquals(dumbRecurrenceGoalId.goalId(), ids.getFirst());
  }

  /**
   * Run a spec with one procedure in it with required params but no args set
   * Should fail scheduling run
   */
  @Test
  void executeSchedulingRunWithoutArguments() throws IOException {
    final var resp = hasura.awaitFailingScheduling(specId);
    final var message = resp.reason().getString("message");
    assertTrue(message.contains("java.lang.RuntimeException: gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException: Invalid arguments for input type \"DumbRecurrenceGoal\": extraneous arguments: [], unconstructable arguments: [], missing arguments: [MissingArgument[parameterName=biteSize, schema=IntSchema[]]], valid arguments: [ValidArgument[parameterName=quantity, serializedValue=NumericValue[value=360]]]"));
  }

  /**
   * Run a spec with one procedure in it
   */
  @Test
  void executeSchedulingRunWithArguments() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).add("biteSize", 1).build();

    hasura.updateSchedulingSpecGoalArguments(dumbRecurrenceGoalId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "24:00:00")
    ));

    assertTrue(activities.stream().anyMatch(
        it -> Objects.equals(it.type(), "BiteBanana") && Objects.equals(it.startOffset(), "30:00:00")
    ));
  }

  /**
   * Run a spec with two invocations of the same procedure in it
   */
  @Test
  void executeMultipleInvocationsOfSameProcedure() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).add("biteSize", 1).build();
    hasura.updateSchedulingSpecGoalArguments(dumbRecurrenceGoalId.invocationId(), args);

    final var secondInvocationId = hasura.insertGoalInvocation(dumbRecurrenceGoalId.goalId(), specId);
    hasura.updateSchedulingSpecGoalArguments(secondInvocationId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(4, activities.size());
  }

  /**
   * Run a spec with two procedures in it
   */
  @Test
  void executeMultipleProcedures() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).add("biteSize", 1).build();
    hasura.updateSchedulingSpecGoalArguments(dumbRecurrenceGoalId.invocationId(), args);

    final var secondProcedure = hasura.createSchedulingSpecProcedure(
        "Test Scheduling Procedure 2",
        dumbRecurrenceGoalJarId,
        specId,
        1);

    hasura.updateSchedulingSpecGoalArguments(secondProcedure.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(4, activities.size());
  }

  /**
   * Run a spec with one EDSL goal and one procedure
   */
  @Test
  void executeEDSLAndProcedure() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 4).add("biteSize", 1).build();
    hasura.updateSchedulingSpecGoalArguments(dumbRecurrenceGoalId.invocationId(), args);

    final String recurrenceGoalDefinition =
        """
        export default function myGoal() {
          return Goal.ActivityRecurrenceGoal({
            activityTemplate: ActivityTemplates.PeelBanana({peelDirection: 'fromStem'}),
            interval: Temporal.Duration.from({hours:1})
        })}""";

    hasura.createSchedulingSpecGoal(
        "Recurrence Scheduling Test Goal",
        recurrenceGoalDefinition,
        specId,
        1);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(52, activities.size());
  }

  /**
   * Run a spec with one procedure and make sure the activity names are saved to the database
   */
  @Test
  void saveActivityName() throws IOException {
    final var args = Json.createObjectBuilder().add("quantity", 2).add("biteSize", 1).build();
    hasura.updateSchedulingSpecGoalArguments(dumbRecurrenceGoalId.invocationId(), args);

    hasura.awaitScheduling(specId);

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    assertEquals(2, activities.size());
    assertEquals("It's a bite banana activity", activities.getFirst().name());
  }

  /**
   * Run a spec that includes unfinished activities
   */
  @Test
  void IncludesUnfinishedActivities() throws IOException {
    // Disable the recurrence goal to simplify scheduling results
    hasura.updateSchedulingSpecEnabled(dumbRecurrenceGoalId.invocationId(), false);

    // Add the decomposition goal that will generate unfinished spans
    hasura.createSchedulingSpecProcedure(
        "Unfinished Spans Goal",
        decompositionGoalJarId,
        specId,
        0);

    final var schedulingResp = hasura.awaitScheduling(specId);
    assertEquals("complete", schedulingResp.status());

    final var plan = hasura.getPlan(planId);
    final var activities = plan.activityDirectives();

    // There should be 3 activities
    assertEquals(3, activities.size());

    assertEquals(1, activities.stream()
                              .filter(a -> a.type().equals("parent")
                                           && a.name().equals("Placed Parent Activity"))
                              .count());
    assertEquals(1, activities.stream()
                              .filter(a -> a.type().equals("child")
                                           && a.name().equals("Placed Child Activity"))
                              .count());
    assertEquals(1, activities.stream()
                              .filter(a -> a.type().equals("grandchild")
                                           && a.name().equals("Placed Grandchild Activity"))
                              .count());

    // Check the posted simulation results
    // There should be 6 spans, all unfinished (3 from parent, 2 from child, one from grandchild)
    final var simResults =  hasura.getSimulationDatasetByDatasetId(schedulingResp.datasetId());
    assertEquals(SimulationDataset.SimulationStatus.success, simResults.status());
    assertEquals(6, simResults.activities().size());
    for(final var act : simResults.activities()) {
      assertNull(act.duration());
    }
  }
}
