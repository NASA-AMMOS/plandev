package gov.nasa.jpl.aerie.e2e.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.types.*;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.stream.StreamSupport;

/**
 * Hasura API request functions
 */
public class HasuraRequests implements AutoCloseable {
  private static final String hasuraAdminSecret = System.getenv("HASURA_GRAPHQL_ADMIN_SECRET");
  private static final Map<String, String> defaultHeaders = Map.of("x-hasura-role", "aerie_admin", "x-hasura-user-id", "Aerie Legacy");

  private final APIRequestContext request;

  public HasuraRequests(Playwright playwright) {
    request = playwright.request().newContext(
            new APIRequest.NewContextOptions()
                    .setBaseURL(BaseURL.HASURA.url));
  }

  @Override
  public void close(){
    request.dispose();
  }

  /**
   * Make a request to Hasura as the `Aerie Legacy` user using the role `aerie_admin`
   * @param query the GQL query or mutation to be executed
   * @param variables a ObjectNode containing the query variables for the query
   * @return a ObjectNode containing the response from Hasura
   * @throws IOException if the response status is not 200
   */
  private ObjectNode makeRequest(GQL query, ObjectNode variables)
  throws IOException {
    return makeRequest( query, variables, defaultHeaders);
  }

  /**
   * Make a request to Hasura using custom headers
   * @param query the GQL query or mutation to be executed
   * @param variables a ObjectNode containing the query variables for the query
   * @param headers a Map containing the custom headers
   * @return a ObjectNode containing the response from Hasura
   * @throws IOException if the response status is not 200
   */
  private ObjectNode makeRequest(
      GQL query,
      ObjectNode variables,
      Map<String, String> headers
  ) throws IOException {
    // Build Payload
    final String data = JsonNodeFactory.instance.objectNode()
                            .put("query", query.query)
                            .set("variables", variables)
                            
                            .toString(); // Payloads must be JSON Strings and not JSON Objects

    // Set Up Request
    final RequestOptions options = RequestOptions.create()
            .setData(data)
            .setHeader("x-hasura-admin-secret", hasuraAdminSecret);
    headers.forEach(options::setHeader);

    final var response = request.post("/v1/graphql", options);

    // Process Response
    if(!response.ok()){
      throw new IOException(response.statusText());
    }

    final ObjectNode bodyJson = (ObjectNode) new ObjectMapper().readTree(response.text());
    if(bodyJson.has("errors")){
      System.err.println("Errors in response: \n" + bodyJson.get("errors"));
      throw new RuntimeException(bodyJson.toString());
    }
    return (ObjectNode) bodyJson.get("data");
  }

  //region Records
  public record ExternalEvent(String key, String event_type_name, String source_key, String derivation_group_name, String start_time, String duration, ObjectNode attributes) {}
  public record ExternalSource(String key, String source_type_name, String derivation_group_name, String valid_at, String start_time, String end_time, String created_at, ObjectNode attributes){}
  //endregion Records

  //region Mission Model
  public int createMissionModel(int jarId, String name, String mission, String version)
  throws IOException, InterruptedException
  {
    final var insertModelBuilder = JsonNodeFactory.instance.objectNode()
                                     .put("jar_id", jarId)
                                     .put("name", name)
                                     .put("mission", mission)
                                     .put("version", version);
    final var variables = JsonNodeFactory.instance.objectNode().set("model", insertModelBuilder);
    final var data = makeRequest(GQL.CREATE_MISSION_MODEL, variables).get("insert_mission_model_one");
    final int modelId = data.get("id").intValue();

    // Wait for all events associated with model upload to finish
    // Necessary for TS compilation
    awaitModelEventLogs(modelId);
    return modelId;
  }

  public void deleteMissionModel(int id) throws IOException {
    makeRequest(GQL.DELETE_MISSION_MODEL, JsonNodeFactory.instance.objectNode().put("id", id));
  }

  public EffectiveModelArguments getEffectiveModelArguments(
      int modelId,
      ObjectNode modelArgs
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("modelId", modelId)
                              .set("modelArgs", modelArgs)
                              ;
    final var results = makeRequest(GQL.GET_EFFECTIVE_MODEL_ARGUMENTS, variables)
        .get("getModelEffectiveArguments");
    return EffectiveModelArguments.fromJSON((ObjectNode) results);
  }

  public List<ResourceType> getResourceTypes(int missionModelId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("missionModelId", missionModelId);
    final var data = makeRequest(GQL.GET_RESOURCE_TYPES, variables);
    return StreamSupport.stream(data.get("resource_type").spliterator(), false).map(e -> ResourceType.fromJSON((ObjectNode) e)).toList();
  }

  public List<ActivityType> getActivityTypes(int missionModelId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("missionModelId", missionModelId);
    final var data = makeRequest(GQL.GET_ACTIVITY_TYPES, variables);
    return StreamSupport.stream(data.get("activity_type").spliterator(), false).map(e -> ActivityType.fromJSON((ObjectNode) e)).toList();
  }

  /**
   * Get the Hasura Event Logs for the mission model with a timeout of 30 seconds.
   * @param modelId the mission model to get logs for
   */
  public ModelEventLogs awaitModelEventLogs(int modelId) throws IOException {
    return awaitModelEventLogs(modelId, 30);
  }

  /**
   * Get the Hasura Event Logs for the mission model.
   * @param modelId the mission model to get logs for
   * @param timeout the amount of time to wait for at least one log of each type
   */
  public ModelEventLogs awaitModelEventLogs(int modelId, int timeout) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("modelId", modelId);

    for(int i = 0; i < timeout; ++i){
      final var logs = ModelEventLogs.fromJSON((ObjectNode) makeRequest(GQL.GET_MODEL_EVENT_LOGS, variables).get("mission_model"));

      if(logs.refreshActivityTypesLogs().getLast().pending() ||
         logs.refreshModelParamsLogs().getLast().pending() ||
         logs.refreshResourceTypesLogs().getLast().pending()) {
        try {
          Thread.sleep(1000); // 1s
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      } else {
        return logs;
      }
    }
    throw new TimeoutError("One or more mission model Hausra events did not return after " + timeout + " seconds");
  }
  //endregion

  //region Plan
  public int createPlan(int modelId, String name, String duration, String startTime) throws IOException {
    return createPlan(modelId, name, duration, startTime, defaultHeaders);
  }

  public int createPlan(
      int modelId,
      String name,
      String duration,
      String startTime,
      Map<String, String> headers)
  throws IOException {
    final var insertPlanBuilder = JsonNodeFactory.instance.objectNode()
            .put("model_id", modelId)
            .put("name", name)
            .put("duration", duration)
            .put("start_time", startTime);
    final var variables = JsonNodeFactory.instance.objectNode().set("plan", insertPlanBuilder);
    return makeRequest(GQL.CREATE_PLAN, variables, headers).get("insert_plan_one").get("id").intValue();
  }

  public Plan getPlan(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("id", planId);
    final var plan = makeRequest(GQL.GET_PLAN, variables).get("plan");
    return Plan.fromJSON((ObjectNode) plan);
  }

  public int getPlanRevision(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("id", planId);
    return makeRequest(GQL.GET_PLAN_REVISION, variables).get("plan").get("revision").intValue();
  }

  public void deletePlan(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("id", planId);
    makeRequest(GQL.DELETE_PLAN, variables);
  }

  public int insertActivityDirective(int planId, String type, String startOffset, ObjectNode arguments, ObjectNode ...extraArgs) throws IOException {
    final var insertActivityBuilder = JsonNodeFactory.instance.objectNode()
                                          .put("plan_id", planId)
                                          .put("type", type)
                                          .put("start_offset", startOffset)
                                          .set("arguments", arguments);
    for (final var extraArg : extraArgs) {
      insertActivityBuilder.setAll(extraArg);
    }
    final var variables = JsonNodeFactory.instance.objectNode().set("activityDirectiveInsertInput", insertActivityBuilder);
    return makeRequest(GQL.CREATE_ACTIVITY_DIRECTIVE, variables).get("createActivityDirective").get("id").intValue();
  }

  public void insertActivityInstance(int datasetId, int directiveId, String type, String startOffset, String duration, ObjectNode arguments) throws IOException {
    final var hasuraAdminHeader = Map.of("x-hasura-role", "admin");

    final var insertActivityBuilder = JsonNodeFactory.instance.objectNode()
        .put("span_id", directiveId)
        .put("dataset_id", datasetId)
        .put("type", type)
        .put("start_offset", startOffset)
        .put("duration", duration)
        .add(
            "attributes",
            JsonNodeFactory.instance.objectNode()
                .set("arguments", arguments)
                .put("directiveId", directiveId)
                .set("computedAttributes", JsonNodeFactory.instance.objectNode())
        );
    final var variables = JsonNodeFactory.instance.objectNode().set("span", insertActivityBuilder);
    makeRequest(GQL.INSERT_SPAN, variables, hasuraAdminHeader);
  }

  public void updateActivityDirectiveArguments(int planId, int activityId, ObjectNode arguments) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .put("id", activityId)
                              .set("arguments", arguments)
                              ;

    makeRequest(GQL.UPDATE_ACTIVITY_DIRECTIVE_ARGUMENTS, variables);
  }

  public void deleteActivity(int planId, int activityId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .put("id", activityId)
                              ;
    makeRequest(GQL.DELETE_ACTIVITY_DIRECTIVE, variables);
  }

  public EffectiveActivityArguments getEffectiveActivityArguments(
      int modelId,
      String activityType,
      ObjectNode activityArguments
  ) throws IOException {
    final var effectiveArgs =  getEffectiveActivityArgumentsBulk(modelId, List.of(Pair.of(activityType, activityArguments)));
    assert(effectiveArgs.size() == 1);
    return effectiveArgs.get(0);
  }

  public List<EffectiveProceduralArguments> getEffectiveProceduralGoalsArgumentsBulk(
      List<Pair<Integer, ObjectNode>> proceduralGoalIds
  ) throws IOException {
    final var proceduresBuilder = JsonNodeFactory.instance.arrayNode();
    proceduralGoalIds.forEach(goal -> proceduresBuilder.add(JsonNodeFactory.instance.objectNode()
                                                         .set("id", goal.getLeft())
                                                         .put("revision", 0)
                                                         .set("arguments", goal.getRight())));
    final var variables = JsonNodeFactory.instance.objectNode()
                              .set("arguments", proceduresBuilder)
                              ;
    return makeRequest(GQL.GET_EFFECTIVE_PROCEDURAL_GOALS_ARGUMENTS_BULK, variables)
        .get("getSchedulingProcedureEffectiveArgumentsBulk")
StreamSupport.stream(        .spliterator(), false).map(e -> EffectiveProceduralArguments.fromJSON((ObjectNode) e)).toList();
  }

  public List<EffectiveProceduralArguments> getEffectiveProceduralConstraintsArgumentsBulk(
      List<Pair<Integer, ObjectNode>> proceduralGoalIds
  ) throws IOException {
    final var proceduresBuilder = JsonNodeFactory.instance.arrayNode();
    proceduralGoalIds.forEach(goal -> proceduresBuilder.add(JsonNodeFactory.instance.objectNode()
                                                                .set("id", goal.getLeft())
                                                                .put("revision", 0)
                                                                .set("arguments", goal.getRight())));
    final var variables = JsonNodeFactory.instance.objectNode()
                              .set("arguments", proceduresBuilder)
                              ;
    return makeRequest(GQL.GET_EFFECTIVE_PROCEDURAL_CONSTRAINTS_ARGUMENTS_BULK, variables)
        .get("getConstraintProcedureEffectiveArgumentsBulk")
StreamSupport.stream(        .spliterator(), false).map(e -> EffectiveProceduralArguments.fromJSON((ObjectNode) e)).toList();
  }

  public List<EffectiveActivityArguments> getEffectiveActivityArgumentsBulk(
      int modelId,
      List<Pair<String, ObjectNode>> activities
  ) throws IOException {
    final var activitiesBuilder = JsonNodeFactory.instance.arrayNode();
    activities.forEach(pair -> activitiesBuilder.add(JsonNodeFactory.instance.objectNode()
                                                            .set("activityTypeName", pair.getLeft())
                                                            .set("activityArguments", pair.getRight())));
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("modelId", modelId)
                              .set("activities", activitiesBuilder)
                              ;
    return makeRequest(GQL.GET_EFFECTIVE_ACTIVITY_ARGUMENTS_BULK, variables)
        .get("getActivityEffectiveArgumentsBulk")
StreamSupport.stream(        .spliterator(), false).map(e -> EffectiveActivityArguments.fromJSON((ObjectNode) e)).toList();
  }

  public Map<Long, ActivityValidation> getActivityValidations(final int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("planId", planId)
                              ;
    final ArrayNode response = makeRequest(GQL.GET_ACTIVITY_VALIDATIONS, variables)
        .get("activity_directive_validations");
    final var res = new HashMap<Long, ActivityValidation>();
    for (final var object : response) {
      res.put(
          (long) (ObjectNode) object.get("directive_id").intValue(),
          ActivityValidation.fromJSON((ObjectNode) object));
    }
    return res;
  }
  //endregion

  //region Simulation
  private SimulationResponse simulate(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("plan_id", planId);
    return SimulationResponse.fromJSON((ObjectNode) makeRequest(GQL.SIMULATE, variables).get("simulate"));
  }

  private SimulationResponse simulateForce(int planId, Boolean force) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("plan_id", planId);
    if (force == null) {
      variables.set("force", NullNode.getInstance());
    } else {
      variables.put("force", force);
    }
    return SimulationResponse.fromJSON((ObjectNode) makeRequest(GQL.SIMULATE_FORCE, variables).get("simulate"));
  }

  private SimulationDataset cancelSimulation(int simDatasetId, int timeout) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("id", simDatasetId);
    makeRequest(GQL.CANCEL_SIMULATION, variables);
    for(int i = 0; i < timeout; ++i){
      try {
        Thread.sleep(1000); //1s
      } catch (InterruptedException ex) {throw new RuntimeException(ex);}
      final var response = getSimulationDataset(simDatasetId);
      // If reason is present, that means that the simulation results have posted
      // and we are not just seeing the side effects of `GQL.CANCEL_SIMULATION`
      if(response.canceled() && response.reason().isPresent()) return response;
    }
    throw new TimeoutError("Canceling simulation timed out after " + timeout + " seconds");
  }

  /**
   * Simulate the specified plan with a timeout of 30 seconds
   */
  public SimulationResponse awaitSimulation(int planId) throws IOException {
    return awaitSimulation(planId, 30);
  }

  /**
   * Simulate the specified plan with a set timeout
   * @param planId the plan to simulate
   * @param timeout the length of the timeout, in seconds
   */
  public SimulationResponse awaitSimulation(int planId, int timeout) throws IOException {
    for(int i = 0; i < timeout; ++i){
      final var response = simulate(planId);
        switch (response.status()) {
          case "pending", "incomplete" -> {
            try {
              Thread.sleep(1000); // 1s
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
          case "complete" -> {
            return response;
          }
          default -> fail("Simulation returned bad status " + response.status() + " with reason " +response.reason());
        }
    }
    throw new TimeoutError("Simulation timed out after " + timeout + " seconds");
  }

  /**
   * Simulate the specified plan, potentially forcibly, with a timeout of 30 seconds
   * @param planId the plan to simulate
   * @param force whether to forcibly resimulate in the event of an existing dataset.
   */
  public SimulationResponse awaitSimulation(int planId, Boolean force) throws IOException {
    return awaitSimulation(planId, force, 30);
  }

  /**
   * Simulate the specified plan, potentially forcibly, with a set timeout
   * @param planId the plan to simulate
   * @param force whether to forcibly resimulate in the event of an existing dataset.
   * @param timeout the length of the timeout, in seconds
   */
  public SimulationResponse awaitSimulation(int planId, Boolean force, int timeout) throws IOException {
    for (int i = 0; i < timeout; ++i) {
      final SimulationResponse response;
      // Only use force on the initial request to avoid an infinite loop of making new sim requests
      if (i == 0) {
        response = simulateForce(planId, force);
      } else {
        response = simulate(planId);
      }
      switch (response.status()) {
        case "pending", "incomplete" -> {
          try {
            Thread.sleep(1000); // 1s
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        case "complete" -> {
          return response;
        }
        default -> fail("Simulation returned bad status " + response.status() + " with reason " + response.reason());
      }
    }
    throw new TimeoutError("Simulation timed out after " + timeout + " seconds");
  }

  /**
   * Start and immediately cancel a simulation with a timeout of 30 seconds
   * @param planId the plan to simulate
   */
  public SimulationDataset cancelingSimulation(int planId) throws IOException {
    return cancelingSimulation(planId, 30);
  }

  /**
   * Start and immediately cancel a simulation with a set timeout
   * @param planId the plan to simulate
   * @param timeout the length of the timeout, in seconds
   */
  public SimulationDataset cancelingSimulation(int planId, int timeout) throws IOException {
    for(int i = 0; i < timeout; ++i){
      final var response = simulate(planId);
        switch (response.status()) {
          case "pending" -> {
            try {
              Thread.sleep(1000); // 1s
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
          case "incomplete" -> {
            try {
              Thread.sleep(1000); // 1s to give the simulation time to do some work
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return cancelSimulation(response.simDatasetId(), timeout-i);
          }
          case "complete" -> fail("Simulation completed before it could be canceled");
          default -> fail("Simulation returned bad status " + response.status() + " with reason " +response.reason());
        }
    }
    throw new TimeoutError("Simulation timed out after " + timeout + " seconds");
  }

    /**
   * Simulate the specified plan with a timeout of 30 seconds.
   * Used when the simulation is expected to fail.
   */
  public SimulationResponse awaitFailingSimulation(int planId) throws IOException {
    return awaitFailingSimulation(planId, 30);
  }

  /**
   * Simulate the specified plan with a set timeout.
   * Used when the simulation is expected to fail.
   *
   * @param planId the plan to simulate
   * @param timeout the length of the timeout, in seconds
   */
  public SimulationResponse awaitFailingSimulation(int planId, int timeout) throws IOException {
    for(int i = 0; i < timeout; ++i){
      final var response = simulate(planId);
        switch (response.status()) {
          case "pending", "incomplete" -> {
            try {
              Thread.sleep(1000); // 1s
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
          case "failed" -> {
            return response;
          }
          default -> fail("Simulation returned bad status " + response.status() + " with reason " +response.reason());
        }
    }
    throw new TimeoutError("Simulation timed out after " + timeout + " seconds");
  }

  public int getSimulationId(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("plan_id", planId);
    return makeRequest(GQL.GET_SIMULATION_ID, variables).get("simulation").get(0).get("id").intValue();
  }

  public SimulationConfiguration getSimConfig(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("planId", planId);
    final var simConfig = makeRequest(GQL.GET_SIMULATION_CONFIGURATION, variables).get("sim_config");
    assertEquals(1, simConfig.size());
    return SimulationConfiguration.fromJSON((ObjectNode) simConfig.get(0));
  }

  public int insertAndAssociateSimTemplate(int modelId, String description, ObjectNode arguments, int simConfigId)
  throws IOException
  {
    final var insertSimTemplateBuilder = JsonNodeFactory.instance.objectNode()
                                             .put("model_id", modelId)
                                             .put("description", description)
                                             .set("arguments", arguments);
    final var insertVariables = JsonNodeFactory.instance.objectNode().set("simulationTemplate", insertSimTemplateBuilder);
    final var templateId = makeRequest(GQL.INSERT_SIMULATION_TEMPLATE, insertVariables)
        .get("template")
        .get("id").intValue();

    final var assignVariables = JsonNodeFactory.instance.objectNode()
                                    .put("simulation_id", simConfigId)
                                    .put("simulation_template_id", templateId)
                                    ;
    makeRequest(GQL.ASSIGN_TEMPLATE_TO_SIMULATION, assignVariables);
    return templateId;
  }

  public void deleteSimTemplate(int templateId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("templateId", templateId)
                              ;
    makeRequest(GQL.DELETE_SIMULATION_PRESET, variables);
  }

  public void updateSimArguments(int planId, ObjectNode arguments) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .set("arguments", arguments)
                              ;
    makeRequest(GQL.UPDATE_SIMULATION_ARGUMENTS, variables);
  }

  public void updateSimBounds(int planId, String simStartTime, String simEndTime) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .put("simulation_start_time", simStartTime)
                              .put("simulation_end_time", simEndTime)
                              ;
    makeRequest(GQL.UPDATE_SIMULATION_BOUNDS, variables);
  }
  //endregion

  //region Scheduling
  private SchedulingResponse schedule(int schedulingSpecId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("specificationId", schedulingSpecId);
    final var data = makeRequest(GQL.SCHEDULE, variables).get("schedule");
    return SchedulingResponse.fromJSON((ObjectNode) data);
  }

  private SchedulingRequest cancelSchedulingRun(int analysisId, int timeout) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("analysis_id", analysisId);
    makeRequest(GQL.CANCEL_SCHEDULING, variables);
    for(int i = 0; i <timeout; ++i) {
      try {
        Thread.sleep(1000); //1s
      } catch (InterruptedException ex) {throw new RuntimeException(ex);}
      final var response = getSchedulingRequest(analysisId);
      // If reason is present, that means that the scheduler has posted
      // and we are not just seeing the side effects of `GQL.CANCEL_SCHEDULING`
      if(response.canceled() && response.reason().isPresent()) return response;
    }
    throw new TimeoutError("Canceling scheduling timed out after " + timeout + " seconds");
  }

  private SchedulingRequest getSchedulingRequest(int analysisId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("analysisId", analysisId)
                              ;
    final var data = makeRequest(GQL.GET_SCHEDULING_REQUEST, variables).get("scheduling_request_by_pk");
    return SchedulingRequest.fromJSON((ObjectNode) data);
  }

  /**
   * Run scheduling on the specified scheduling specification with a timeout of 30 seconds
   */
  public SchedulingResponse awaitScheduling(int schedulingSpecId) throws IOException {
    return awaitScheduling(schedulingSpecId, 30);
  }

  /**
   * Run scheduling on the specified scheduling specification with a timeout of 30 seconds
   * @param timeout the length of the timeout, in seconds
   */
  public SchedulingResponse awaitScheduling(int schedulingSpecId, int timeout) throws IOException {
    for(int i = 0; i < timeout; ++i){
      final var response = schedule(schedulingSpecId);
        switch (response.status()) {
          case "pending", "incomplete" -> {
            try {
              Thread.sleep(1000); // 1s
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
          case "complete" -> {
            return response;
          }
          default -> fail("Scheduling returned bad status " + response.status() + " with reason " +response.reason());
        }
    }
    throw new TimeoutError("Scheduling timed out after " + timeout + " seconds");
  }

  /**
   * Run scheduling on the specified scheduling specification with a timeout of 30 seconds
   * Used when the scheduling run is expected to fail.
   */
  public SchedulingResponse awaitFailingScheduling(int schedulingSpecId) throws IOException {
    return awaitFailingScheduling(schedulingSpecId, 30);
  }

  /**
   * Run scheduling on the specified scheduling specification
   * Used when the scheduling run is expected to fail.
   */
  public SchedulingResponse awaitFailingScheduling(int schedulingSpecId, int timeout) throws IOException {
    for(int i = 0; i < timeout; ++i){
      final var response = schedule(schedulingSpecId);
      switch (response.status()) {
        case "pending", "incomplete" -> {
          try {
            Thread.sleep(1000); // 1s
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        case "failed" -> {
          return response;
        }
        default -> fail("Scheduling returned bad status " + response.status() + " with reason " +response.reason());
      }
    }
    throw new TimeoutError("Scheduling timed out after " + timeout + " seconds");
  }

  /**
   * Start and immediately cancel a scheduling run with a timeout of 30 seconds
   * @param schedulingSpecId the scheduling specification to use
   *
   */
  public SchedulingRequest cancelingScheduling(int schedulingSpecId) throws IOException {
    return cancelingScheduling(schedulingSpecId, 30);
  }

  /**
   * Start and immediately cancel a scheduling run with a set timeout
   * @param schedulingSpecId the scheduling specification to use
   * @param timeout the length of the timeout, in seconds
   */
  public SchedulingRequest cancelingScheduling(int schedulingSpecId, int timeout) throws IOException {
    for(int i = 0; i < timeout; ++i) {
      final var response = schedule(schedulingSpecId);
      switch (response.status()) {
        case "pending" -> {
          try {
            Thread.sleep(1000); //1s
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        case "incomplete" -> {
          return cancelSchedulingRun(response.analysisId(), timeout - i);
        }
        case "complete" -> fail("Scheduling completed before it could be canceled");
        default -> fail("Scheduling returned bad status " + response.status() + " with reason " +response.reason());
      }
    }
    throw new TimeoutError("Scheduling timed out after " + timeout + " seconds");
  }

  public void deleteSchedulingGoal(int goalId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("goalId", goalId)
                              ;
    makeRequest(GQL.DELETE_SCHEDULING_GOAL, variables);
  }

  public int getSchedulingSpecId(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("planId", planId);
    final var spec = makeRequest(GQL.GET_SCHEDULING_SPECIFICATION_ID, variables).get("scheduling_spec");
    assertEquals(1, spec.size());
    return spec.get(0).get("id").intValue();
  }

  public List<Integer> getSchedulingSpecGoalIds(int specId) throws IOException {
    final var vars = JsonNodeFactory.instance.objectNode().put("specId", specId);
    final var goals = makeRequest(GQL.GET_SCHEDULING_SPECIFICATION_GOALS, vars).get("goals");

    return StreamSupport.stream(goals.spliterator(), false).map(e -> e.get("goal_id").intValue()).toList();
  }

  public void updatePlanRevisionSchedulingSpec(int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("planId", planId)
                              .put("planRev", getPlanRevision(planId))
                              ;
    makeRequest(GQL.UPDATE_SCHEDULING_SPECIFICATION_PLAN_REVISION, variables);
  }

  public GoalInvocationId createSchedulingSpecProcedure(
      String name,
      int jarId,
      int specificationId,
      int priority
  ) throws IOException {
    return createSchedulingSpecProcedure(name, jarId, specificationId, priority, true);
  }

  public ConstraintInvocationId createConstraintSpecProcedure(
      String name,
      int jarId,
      int planId
  ) throws IOException {
    final var specGoalBuilder = JsonNodeFactory.instance.objectNode()
                                    .set("constraint_metadata", JsonNodeFactory.instance.objectNode()
                                             .set("data", JsonNodeFactory.instance.objectNode()
                                                      .put("name", name)
                                                      .put("description", "")
                                                      .set("versions", JsonNodeFactory.instance.objectNode()
                                                               .set("data", JsonNodeFactory.instance.arrayNode()
                                                                        .add(JsonNodeFactory.instance.objectNode()
                                                                                 .put("type", "JAR")
                                                                                 .put("uploaded_jar_id", jarId)
                                                                        )))))
                                    .put("plan_id", planId);
    final var variables = JsonNodeFactory.instance.objectNode().set("constraint", specGoalBuilder);
    final var resp =  makeRequest(GQL.INSERT_PLAN_SPEC_CONSTRAINT, variables)
        .get("constraint");
    return new ConstraintInvocationId(
        resp.get("constraint_id").intValue(),
        resp.get("invocation_id").intValue()
    );
  }

  public GoalInvocationId createSchedulingSpecProcedure(
      String name,
      int jarId,
      int specificationId,
      int priority,
      boolean simulateAfter
  ) throws IOException {
    final var specGoalBuilder = JsonNodeFactory.instance.objectNode()
                                    .set("goal_metadata", JsonNodeFactory.instance.objectNode()
                                             .set("data", JsonNodeFactory.instance.objectNode()
                                                      .put("name", name)
                                                      .put("description", "")
                                                      .set("versions", JsonNodeFactory.instance.objectNode()
                                                               .set("data", JsonNodeFactory.instance.arrayNode()
                                                                        .add(JsonNodeFactory.instance.objectNode()
                                                                                 .put("type", "JAR")
                                                                                 .put("uploaded_jar_id", jarId)
                                                                        )))))
                                    .put("specification_id", specificationId)
                                    .put("priority", priority)
                                    .put("simulate_after", simulateAfter);
    final var variables = JsonNodeFactory.instance.objectNode().set("spec_goal", specGoalBuilder);
    final var resp =  makeRequest(GQL.CREATE_SCHEDULING_SPEC_GOAL, variables)
        .get("insert_scheduling_specification_goals_one");

    return GoalInvocationId.fromJSON((ObjectNode) resp);
  }

  public GoalInvocationId insertGoalInvocation(int goalId, int specificationId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                                    .put("goal_id", goalId)
                                    .put("specification_id", specificationId)
                                    ;

    final var resp = makeRequest(GQL.CREATE_SCHEDULING_SPEC_GOAL_INVOCATION, variables)
        .get("insert_scheduling_specification_goals_one");

    return GoalInvocationId.fromJSON((ObjectNode) resp);
  }

  public GoalInvocationId createSchedulingSpecGoal(
      String name,
      String definition,
      int specificationId,
      int priority
  ) throws IOException {
    return createSchedulingSpecGoal(name, definition, "", specificationId, priority);
  }

  public GoalInvocationId createSchedulingSpecGoal(
      String name,
      String definition,
      String description,
      int specificationId,
      int priority
  ) throws IOException
  {
    return createSchedulingSpecGoal(name, definition, description, specificationId, priority, true);
  }

  public GoalInvocationId createSchedulingSpecGoal(
      String name,
      String definition,
      String description,
      int specificationId,
      int priority,
      boolean simulateAfter
  ) throws IOException {
    final var specGoalBuilder = JsonNodeFactory.instance.objectNode()
                                    .set("goal_metadata", JsonNodeFactory.instance.objectNode()
                                             .set("data", JsonNodeFactory.instance.objectNode()
                                                      .put("name", name)
                                                      .put("description", description)
                                                      .set("versions", JsonNodeFactory.instance.objectNode()
                                                               .set("data", JsonNodeFactory.instance.arrayNode()
                                                                        .add(JsonNodeFactory.instance.objectNode()
                                                                                 .put("definition", definition))))))
                                    .put("specification_id", specificationId)
                                    .put("simulate_after", simulateAfter)
                                    .put("priority", priority);
    final var variables = JsonNodeFactory.instance.objectNode().set("spec_goal", specGoalBuilder);
    final var resp =  makeRequest(GQL.CREATE_SCHEDULING_SPEC_GOAL, variables)
            .get("insert_scheduling_specification_goals_one");

    return GoalInvocationId.fromJSON((ObjectNode) resp);
  }

  public int updateGoalDefinition(int goalId, String definition) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("goal_id", goalId)
                              .put("definition", definition)
                              ;
    return makeRequest(GQL.UPDATE_GOAL_DEFINITION, variables).get("definition").get("revision").intValue();
  }

  public void updateConstraintArguments(int constraintId, ObjectNode arguments) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("constraint_id", constraintId)
                              .set("arguments", arguments)
                              ;
    makeRequest(GQL.UPDATE_CONSTRAINT_ARGUMENTS, variables);
  }

  public void updateSchedulingSpecGoalArguments(int invocationId, ObjectNode arguments) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("goal_invocation_id", invocationId)
                              .set("arguments", arguments)
                              ;
    makeRequest(GQL.UPDATE_SCHEDULING_SPEC_GOALS_ARGUMENTS, variables);
  }

  public void updateSchedulingSpecEnabled(int invocationId, boolean enabled) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("goal_invocation_id", invocationId)
                              .put("enabled", enabled)
                              ;
    makeRequest(GQL.UPDATE_SCHEDULING_SPEC_GOALS_ENABLED, variables);
  }

  public void updateSchedulingSpecVersion(int invocationId, int version) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("goal_invocation_id", invocationId)
                              .put("goal_revision", version)
                              ;
    makeRequest(GQL.UPDATE_SCHEDULING_SPEC_GOALS_VERSION, variables);
  }

  public SchedulingDSLTypesResponse getSchedulingDslTypeScript(int missionModelId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("missionModelId", missionModelId)
                              ;
    return SchedulingDSLTypesResponse.fromJSON((ObjectNode) makeRequest(GQL.GET_SCHEDULING_DSL_TYPESCRIPT, variables).get("schedulingDslTypescript"));
  }

  public SchedulingDSLTypesResponse getSchedulingDslTypeScript(int missionModelId, int planId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("missionModelId", missionModelId)
                              .put("planId", planId)
                              ;
    return SchedulingDSLTypesResponse.fromJSON((ObjectNode) makeRequest(GQL.GET_SCHEDULING_DSL_TYPESCRIPT, variables).get("schedulingDslTypescript"));
  }
  //endregion

  //region Simulation Datasets
  public SimulationDataset getSimulationDataset(int simDatasetId) throws IOException {
    final var data = makeRequest(GQL.GET_SIMULATION_DATASET, JsonNodeFactory.instance.objectNode().put("id", simDatasetId))
            .get("simulationDataset");
    return SimulationDataset.fromJSON((ObjectNode) data);
  }
  public SimulationDataset getSimulationDatasetByDatasetId(int datasetId) throws IOException {
    final var data = makeRequest(
            GQL.GET_SIMULATION_DATASET_BY_DATASET_ID,
            JsonNodeFactory.instance.objectNode().put("id", datasetId))
            .get("simulation_dataset");
    assert(data.size() == 1);
    return SimulationDataset.fromJSON((ObjectNode) data.get(0));
  }
  public Map<String, List<ProfileSegment>> getProfiles(int datasetId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("datasetId", datasetId);
    final var profiles = makeRequest(GQL.GET_PROFILES, variables).get("profile");

    // Process Profile Map
    final var map = new HashMap<String, List<ProfileSegment>>();
    for(final var entry : profiles) {
      final ObjectNode e = (ObjectNode) entry;
      final String name = e.get("name").textValue();
      map.put(name, StreamSupport.stream(e.get("profile_segments").spliterator(), false).map(seg -> ProfileSegment.fromJSON((ObjectNode) seg)).toList());
    }
    return map;
  }

  public Map<String, Topic> getTopicsEvents(int datasetId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("datasetId", datasetId);
    final var topics = makeRequest(GQL.GET_TOPIC_EVENTS, variables).get("topic");
    final var topicList = StreamSupport.stream(topics.spliterator(), false).map(e -> Topic.fromJSON((ObjectNode) e)).toList();
    // Collect into map for ease of use
    return topicList.stream().collect(Collectors.toMap(Topic::name, Function.identity()));
  }

  public int insertSimDataset(
      int simId,
      String simStartTime,
      String simEndTime,
      String status,
      ObjectNode simArguments,
      int planRevision
  ) throws IOException {
    final var insertSimDatasetBuilder = JsonNodeFactory.instance.objectNode()
                                          .put("simulation_id", simId)
                                          .put("simulation_start_time", simStartTime)
                                          .put("simulation_end_time", simEndTime)
                                          .put("status", status)
                                          .set("arguments", simArguments)
                                          .put("plan_revision", planRevision);
    final var variables = JsonNodeFactory.instance.objectNode().set("simulationDataset", insertSimDatasetBuilder);
    // Only the Hasura Admin role may insert into this table
    return makeRequest(GQL.INSERT_SIMULATION_DATASET, variables, Map.of("x-hasura-role", "admin"))
        .get("simulation_dataset")
        .get("dataset_id").intValue();
  }

  public void insertProfile(
      int datasetId,
      String name,
      String duration,
      ObjectNode type,
      List<ProfileSegment> segments
  ) throws IOException
  {
    final var hasuraAdminHeader = Map.of("x-hasura-role", "admin");
    // Insert Profile
    final var profileVariables = JsonNodeFactory.instance.objectNode()
                                     .put("datasetId", datasetId)
                                     .put("duration", duration)
                                     .put("name", name)
                                     .put("type", type)
                                     ;
    final int profileId = makeRequest(GQL.INSERT_PROFILE, profileVariables, hasuraAdminHeader)
        .get("insert_profile_one")
        .get("id").intValue();

    // Insert Profile Segments
    final var segmentsBuilder = JsonNodeFactory.instance.arrayNode();
    segments.forEach(s -> segmentsBuilder.add(s.toJSON(datasetId, profileId)));
    final var segmentVariables = JsonNodeFactory.instance.objectNode()
                                     .set("segments", segmentsBuilder)
                                     ;
    makeRequest(GQL.INSERT_PROFILE_SEGMENTS, segmentVariables, hasuraAdminHeader);
  }
  //endregion

  //region External Datasets
  public int insertExternalDataset(
      int planId,
      String datasetStartTimestamp,
      List<ExternalDataset.ProfileInput> profileSet
  ) throws IOException {
    final var profileSetBuilder = JsonNodeFactory.instance.objectNode();
    profileSet.forEach(e -> profileSetBuilder.set(e.name(), e.toJSON()));
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .set("simulation_dataset_id", NullNode.getInstance())
                              .put("dataset_start", datasetStartTimestamp)
                              .set("profile_set", profileSetBuilder)
                              ;
    return makeRequest(GQL.ADD_EXTERNAL_DATASET, variables)
        .get("addExternalDataset")
        .get("datasetId").intValue();
  }

  public int insertExternalDataset(
      int planId,
      int simulationDatasetId,
      String datasetStartTimestamp,
      List<ExternalDataset.ProfileInput> profileSet) throws IOException {
        final var profileSetBuilder = JsonNodeFactory.instance.objectNode();
    profileSet.forEach(e -> profileSetBuilder.set(e.name(), e.toJSON()));
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .put("simulation_dataset_id", simulationDatasetId)
                              .put("dataset_start", datasetStartTimestamp)
                              .set("profile_set", profileSetBuilder)
                              ;
    return makeRequest(GQL.ADD_EXTERNAL_DATASET, variables)
        .get("addExternalDataset")
        .get("datasetId").intValue();
  }

  public void extendExternalDataset(int datasetId, List<ExternalDataset.ProfileInput> profileSet) throws IOException {
    final var profileSetBuilder = JsonNodeFactory.instance.objectNode();
    profileSet.forEach(e -> profileSetBuilder.set(e.name(), e.toJSON()));
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("dataset_id", datasetId)
                              .set("profile_set", profileSetBuilder)
                              ;
    makeRequest(GQL.EXTEND_EXTERNAL_DATASET, variables);
  }

  public ExternalDataset getExternalDataset(int planId, int datasetId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .put("dataset_id", datasetId)
                              ;
    final var dataset = makeRequest(GQL.GET_EXTERNAL_DATASET, variables).get("externalDataset");
    return ExternalDataset.fromJSON((ObjectNode) dataset);
  }

  public void deleteExternalDataset(int planId, int datasetId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("plan_id", planId)
                              .put("dataset_id", datasetId)
                              ;
    makeRequest(GQL.DELETE_EXTERNAL_DATASET, variables);
  }
  //endregion

  // region External Events
  public String insertExternalSourceType(
      String name,
      String attributeSchema
  ) throws IOException {
    final var insertExternalSourceTypeBuilder = JsonNodeFactory.instance.objectNode()
                              .put("name", name)
                              .put("attribute_schema", attributeSchema)
                              ;
    final var variables = JsonNodeFactory.instance.objectNode().set("sourceType", insertExternalSourceTypeBuilder);
    return makeRequest(GQL.CREATE_EXTERNAL_SOURCE_TYPE, variables)
        .get("createExternalSourceType")
        .get("name").textValue();
  }
  public String insertExternalEventType(
      String name,
      String attributeSchema
  ) throws IOException {
    final var insertExternalSourceTypeBuilder = JsonNodeFactory.instance.objectNode()
                                                    .put("name", name)
                                                    .put("attribute_schema", attributeSchema)
                                                    ;
    final var variables = JsonNodeFactory.instance.objectNode().set("eventType", insertExternalSourceTypeBuilder);
    return makeRequest(GQL.CREATE_EXTERNAL_EVENT_TYPE, variables)
        .get("createExternalEventType")
        .get("name").textValue();
  }
  public String insertDerivationGroup(
      String name,
      String sourceTypeName
  ) throws IOException {
    final var insertDerivationGroupBuilder = JsonNodeFactory.instance.objectNode()
                                                    .put("name", name)
                                                    .put("source_type_name", sourceTypeName)
                                                    ;
    final var variables = JsonNodeFactory.instance.objectNode().set("derivationGroup", insertDerivationGroupBuilder);
    return makeRequest(GQL.CREATE_DERIVATION_GROUP, variables)
        .get("createDerivationGroup")
        .get("name").textValue();
  }
  public String insertExternalSource(
    ExternalSource externalSource
  ) throws IOException {
    final var insertExternalSourceBuilder = JsonNodeFactory.instance.objectNode()
        .put("key", externalSource.key())
        .put("source_type_name", externalSource.source_type_name())
        .put("derivation_group_name", externalSource.derivation_group_name())
        .put("valid_at", externalSource.valid_at())
        .put("start_time", externalSource.start_time())
        .put("end_time", externalSource.end_time())
        .put("created_at", externalSource.created_at())
        .set("attributes", externalSource.attributes())
        ;
    final var variables = JsonNodeFactory.instance.objectNode().set("object", insertExternalSourceBuilder);
    return makeRequest(GQL.CREATE_EXTERNAL_SOURCE, variables)
        .get("insertExternalSource")
        .get("key").textValue();
  }
  public ArrayNode insertExternalEvents(
    List<ExternalEvent> externalEvents
  ) throws IOException {
    ArrayNode formattedEvents = JsonNodeFactory.instance.arrayNode();
    for (ExternalEvent e : externalEvents) {
      formattedEvents.add(
          JsonNodeFactory.instance.objectNode()
              .put("key", e.key())
              .put("event_type_name", e.event_type_name())
              .put("source_key", e.source_key())
              .put("derivation_group_name", e.derivation_group_name())
              .put("start_time", e.start_time())
              .put("duration", e.duration())
              .set("attributes", e.attributes())
              
      );
    }
    final var variables = JsonNodeFactory.instance.objectNode()
                              .set("objects", formattedEvents)
                              ;
    return makeRequest(GQL.CREATE_EXTERNAL_EVENTS, variables)
        .get("insertExternalEvents")
        .get("returning");
  }
  public String insertPlanDerivationGroupAssociation(
      int planId,
      String derivationGroupName
  ) throws IOException {
    final var insertPlanDerivationGroupBuilder = JsonNodeFactory.instance.objectNode()
                                                 .put("plan_id", planId)
                                                 .put("derivation_group_name", derivationGroupName)
                                                 ;
    final var variables = JsonNodeFactory.instance.objectNode().set("source", insertPlanDerivationGroupBuilder);
    return makeRequest(GQL.CREATE_PLAN_DERIVATION_GROUP, variables)
        .get("planExternalSourceLink")
        .get("derivation_group_name").textValue();
  }

  public String deleteExternalSourceType(
    String name
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                                  .put("name", name)
                                  ;
    return makeRequest(GQL.DELETE_EXTERNAL_SOURCE_TYPE, variables)
        .get("deleteExternalSourceType")
        .get("name").textValue();
  }
  public String deleteExternalEventType(
      String name
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("name", name)
                              ;
    return makeRequest(GQL.DELETE_EXTERNAL_EVENT_TYPE, variables)
        .get("deleteExternalEventType")
        .get("name").textValue();
  }
  public ArrayNode deleteDerivationGroup(
      String name
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("name", name)
                              ;
    return makeRequest(GQL.DELETE_DERIVATION_GROUP, variables)
        .get("deleteDerivationGroup")
        .get("returning");
  }

  public String deleteExternalSource(
      String sourceKey,
      String derivationGroupName
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("sourceKey", sourceKey)
                              .put("derivationGroupName", derivationGroupName)
                              ;
    // NOTE: this deletes external events as well, as deletions of sources cascade to their contained events.
    var result = makeRequest(GQL.DELETE_EXTERNAL_SOURCE, variables);

    // some test runs won't successfully add a source, so the result is just null.
    if (!result.has("deleteExteralSource")) {
      return "No source found.";
    }
    return result
        .get("deleteExternalSource")
        .get("key").textValue();
  }

  public ArrayNode deleteEventsBySource(
      String sourceKey,
      String derivationGroupName
  ) throws IOException
  {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("externalSourceKey", sourceKey)
                              .put("derivationGroupName", derivationGroupName)
                              ;
    // NOTE: this deletes external events as well, as deletions of sources cascade to their contained events.
    return makeRequest(GQL.DELETE_EXTERNAL_EVENTS_BY_SOURCE, variables)
        .get("deleteExternalEventsBySource")
        .get("returning");
  }

  public String deleteExternalSource(
      ExternalSource externalSource
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("sourceKey", externalSource.key())
                              .put("derivationGroupName", externalSource.derivation_group_name())
                              ;
    // NOTE: this deletes external events as well, as deletions of sources cascade to their contained events.
    return makeRequest(GQL.DELETE_EXTERNAL_SOURCE, variables)
        .get("deleteExternalSource")
        .get("key").textValue();
  }
  public String deletePlanDerivationGroupAssociation(
      int planId,
      String derivationGroupName
  ) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("planId", planId)
                              .put("derivationGroupName", derivationGroupName)
                              ;
    return makeRequest(GQL.DELETE_PLAN_DERIVATION_GROUP, variables)
        .get("planDerivationGroupLink")
        .get("derivation_group_name").textValue();
  }
  // endregion

  //region Constraints
  /**
   * Check Constraints and only return the set of constraint results
   */
  public List<ConstraintActionResponse.ConstraintRecord> checkConstraintsJustResults(int planID) throws IOException {
    return checkConstraints(planID).constraintsRun();
  }

  /**
   * Check Constraints and only return the set of constraint results
   */
  public List<ConstraintActionResponse.ConstraintRecord> checkConstraintsJustResults(int planID, int simulationDatasetID) throws IOException {
    return checkConstraints(planID, simulationDatasetID).constraintsRun();
  }

  public ConstraintActionResponse checkConstraints(int planID) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("planId", planID)
                              .set("simulationDatasetId", NullNode.getInstance())
                              ;
    final var constraintResults = makeRequest(GQL.CHECK_CONSTRAINTS, variables).get("constraintViolations");
    return ConstraintActionResponse.fromJson((ObjectNode) constraintResults);
  }

  public ConstraintActionResponse checkConstraints(int planID, int simulationDatasetID) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("planId", planID)
                              .put("simulationDatasetId", simulationDatasetID)
                              ;
    final var constraintResults = makeRequest(GQL.CHECK_CONSTRAINTS, variables).get("constraintViolations");
    return ConstraintActionResponse.fromJson((ObjectNode) constraintResults);
  }

  public ConstraintRequest getConstraintRequest(int requestId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("request_id", requestId);
    final var constraintRequest = makeRequest(GQL.GET_CONSTRAINT_REQUEST, variables).get("constraint_request");
    return ConstraintRequest.fromJSON((ObjectNode) constraintRequest);
  }

  public ConstraintInvocationId insertPlanConstraint(String name, int planId, String definition, String description) throws IOException {
    final var constraintInsertBuilder = JsonNodeFactory.instance.objectNode()
                                            .put("plan_id", planId)
                                            .set("constraint_metadata", JsonNodeFactory.instance.objectNode()
                                                     .set("data", JsonNodeFactory.instance.objectNode()
                                                              .put("name", name)
                                                              .put("description", description)
                                                              .set("versions", JsonNodeFactory.instance.objectNode()
                                                                       .set("data", JsonNodeFactory.instance.objectNode()
                                                                                .put("definition", definition)))));
    final var variables = JsonNodeFactory.instance.objectNode().set("constraint", constraintInsertBuilder);
    final var resp = makeRequest(GQL.INSERT_PLAN_SPEC_CONSTRAINT, variables).get("constraint");
    return new ConstraintInvocationId(
      resp.get("constraint_id").intValue(),
      resp.get("invocation_id").intValue()
    );
  }

  public void updatePlanConstraintSpecVersion(int invocationId, int constraintRevision) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("invocation_id", invocationId)
                              .put("constraint_revision", constraintRevision)
                              ;
    makeRequest(GQL.UPDATE_CONSTRAINT_SPEC_VERSION, variables);
  }

  public void updatePlanConstraintSpecEnabled(int invocationId, boolean enabled) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("invocation_id", invocationId)
                              .put("enabled", enabled)
                              ;
    makeRequest(GQL.UPDATE_CONSTRAINT_SPEC_ENABLED, variables);
  }

  public int updateConstraintDefinition(int constraintId, String definition) throws IOException{
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("constraintId", constraintId)
                              .put("constraintDefinition", definition)
                              ;
    return makeRequest(GQL.UPDATE_CONSTRAINT, variables).get("constraint").get("revision").intValue();
  }

  public void deleteConstraint(int constraintId) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("id", constraintId);
    makeRequest(GQL.DELETE_CONSTRAINT, variables);
  }
  //endregion

  //region User and Roles
  public void createUser(User user) throws IOException {
    final var userInsertBuilder = JsonNodeFactory.instance.objectNode()
                                      .put("username", user.name())
                                      .put("default_role", user.defaultRole());
    final var allowedRolesBuilder = JsonNodeFactory.instance.objectNode();
    for(final var role : user.allowedRoles()) {
      allowedRolesBuilder.put("username", user.name());
      allowedRolesBuilder.put("allowed_role", role);
    }

    final var variables = JsonNodeFactory.instance.objectNode()
                              .set("user", userInsertBuilder)
                              .set("allowed_roles", allowedRolesBuilder)
                              ;
    makeRequest(GQL.CREATE_USER, variables);
  }

  public void deleteUser(User user) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("username", user.name());
    makeRequest(GQL.DELETE_USER, variables);
  }

  public void addPlanCollaborator(User user, int planId) throws IOException {
    final var planCollabBuilder = JsonNodeFactory.instance.objectNode().put("planId", planId).put("collaborator", user.name());
    final var variables = JsonNodeFactory.instance.objectNode().set("planCollaboratorInsertInput", planCollabBuilder);
    makeRequest(GQL.ADD_PLAN_COLLABORATOR, variables);
  }

  public ActionPermissionsSet getActionPermissionsForRole(String role) throws IOException {
    final var variables = JsonNodeFactory.instance.objectNode().put("role", role);
    final var permissions = makeRequest(GQL.GET_ROLE_ACTION_PERMISSIONS, variables).get("permissions");
    return ActionPermissionsSet.fromJSON((ObjectNode) permissions.get("action_permissions"));
  }

  public void updateActionPermissionsForRole(String role, ActionPermissionsSet permissions) throws IOException{
    final var variables = JsonNodeFactory.instance.objectNode()
                              .put("role", role)
                              .set("action_permissions", permissions.toJSON())
                              ;
    makeRequest(GQL.UPDATE_ROLE_ACTION_PERMISSIONS, variables);
  }
  //endregion

  //region Workspaces
  /**
   * Creates a mocked command dictionary for the sake of creating Workspaces
   * Create a different method if a non-mocked command dictionary is required for tests.
   * @return the dictionary's database id
   */
  public int createMockCommandDictionary(String mission, String version) throws IOException {
    final var insertCommandDictionaryBuilder = JsonNodeFactory.instance.objectNode()
                                          .put("dictionary_path", "mock_path")
                                          .put("mission", mission)
                                          .put("version", version);

    final var variables = JsonNodeFactory.instance.objectNode().set("cdict", insertCommandDictionaryBuilder);
    // Only the Hasura Admin role may insert into this table
    return makeRequest(GQL.CREATE_MOCK_COMMAND_DICTIONARY, variables, Map.of("x-hasura-role", "admin"))
        .get("dictionary")
        .get("id").intValue();
  }

  /**
   * Creates a mocked parcel for the sake of creating Workspaces
   * Create a different method if a non-mocked parcel is required for tests.
   * @return the parcel's database id
   */
  public int createMockParcel(String parcelName, int cdictId) throws IOException {
    final var insertMockParcelBuilder = JsonNodeFactory.instance.objectNode()
                                            .put("name", parcelName)
                                            .put("command_dictionary_id", cdictId);

    final var variables = JsonNodeFactory.instance.objectNode().set("parcel", insertMockParcelBuilder);

    return makeRequest(GQL.CREATE_PARCEL, variables)
        .get("parcel")
        .get("id").intValue();
  }

  /**
   * Delete a mocked command dictionary.
   */
  public void deleteMockCommandDictionary(int cdictId) throws IOException {
    makeRequest(GQL.DELETE_MOCK_COMMAND_DICTIONARY, JsonNodeFactory.instance.objectNode().put("id", cdictId));
  }

  /**
   * Delete a mocked parcel.
   */
  public void deleteMockParcel(int parcelId) throws IOException {
    makeRequest(GQL.DELETE_PARCEL, JsonNodeFactory.instance.objectNode().put("id", parcelId));
  }

  /**
   * Change the workspace's owner to another user
   */
  public void changeOwner(int workspaceId, User newOwner) throws IOException {
    makeRequest(GQL.CHANGE_WS_OWNER, JsonNodeFactory.instance.objectNode()
                                         .put("id", workspaceId)
                                         .put("newOwner", newOwner.name())
                                         );
  }
  //endregion
}

