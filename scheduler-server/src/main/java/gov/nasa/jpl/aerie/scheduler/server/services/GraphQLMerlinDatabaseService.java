package gov.nasa.jpl.aerie.scheduler.server.services;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.json.BasicParsers;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;
import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.UnfinishedActivity;
import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.Problem;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchMissionModelException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers;
import gov.nasa.jpl.aerie.scheduler.server.http.EventGraphFlattener;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.scheduler.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityAttributesRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityType;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.models.ExternalProfiles;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.MerlinPlan;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanMetadata;
import gov.nasa.jpl.aerie.scheduler.server.models.ProfileSet;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import gov.nasa.jpl.aerie.scheduler.server.models.UnwrappedProfileSet;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static gov.nasa.jpl.aerie.json.BasicParsers.chooseP;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECOND;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.activityAttributesP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.discreteProfileTypeP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.durationFromPGInterval;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.graphQLIntervalFromDuration;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.instantFromStart;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.parseGraphQLTimestamp;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.realDynamicsP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.realProfileTypeP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.GraphQLParsers.simulationArgumentsP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.ProfileParsers.discreteValueSchemaTypeP;
import static gov.nasa.jpl.aerie.scheduler.server.graphql.ProfileParsers.realValueSchemaTypeP;
import static java.util.Map.entry;

/**
 * {@inheritDoc}
 *
 * @param merlinGraphqlURI endpoint of the merlin graphql service that should be used to access all plan data
 */
public record GraphQLMerlinDatabaseService(URI merlinGraphqlURI, String hasuraGraphQlAdminSecret) implements MerlinDatabaseService.OwnerRole {

  /**
   * timeout for http graphql requests issued to aerie
   */
  private static final java.time.Duration httpTimeout = java.time.Duration.ofSeconds(60);

  public record DatasetMetadata(DatasetId datasetId, Duration offsetFromPlanStart){}

  private record SimulationId(long id){}

  private record ProfileRecord(
      long id,
      long datasetId,
      String name,
      Pair<String, ValueSchema> type,
      Duration duration
  ) {}

  private record SpanRecord(
      String type,
      Instant start,
      Optional<Duration> duration,
      Optional<Long> parentId,
      List<Long> childIds,
      ActivityAttributesRecord attributes
  ) {}

  public record SimulationDatasetId(int id){}

  public record DatasetIds(DatasetId datasetId, SimulationDatasetId simulationDatasetId){}

  /**
   * dispatch the given graphql request to aerie and collect the results
   *
   * absorbs any io errors and returns an empty response object in order to keep exception
   * signature of callers cleanly matching the MerlinService interface
   *
   * @param gqlStr the graphQL query or mutation to send to aerie
   * @return the json response returned by aerie, or an empty optional in case of io errors
   */
  private Optional<ObjectNode> postRequest(final String gqlStr) throws IOException, MerlinServiceException {
    try {
      //TODO: (mem optimization) use streams here to avoid several copies of strings
      final var reqBody = JsonNodeFactory.instance.objectNode();
      reqBody.put("query", gqlStr);
      final var httpReq = HttpRequest
          .newBuilder().uri(merlinGraphqlURI).timeout(httpTimeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("Origin", merlinGraphqlURI.toString())
          .header("x-hasura-admin-secret", hasuraGraphQlAdminSecret)
          .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
          .build();
      //TODO: (net optimization) gzip compress the request body if large enough (eg for createAllActs)
      final var httpResp = HttpClient
          .newHttpClient().send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
      if (httpResp.statusCode() != 200) {
        //TODO: how severely to error out if aerie cannot be reached or has a 500 error or json is garbled etc etc?
        return Optional.empty();
      }
      final var mapper = new ObjectMapper();
      final var respBody = (ObjectNode) mapper.readTree(httpResp.body());
      if (respBody.has("errors")) {
        throw new MerlinServiceException(respBody.toString());
      }
      return Optional.of(respBody);
    } catch (final InterruptedException e) {
      //TODO: maybe retry if interrupted? but depends on semantics (eg don't duplicate mutation if not idempotent)
      return Optional.empty();
    }
  }

  protected Optional<ObjectNode> postRequest(final String query, final ObjectNode variables)
  throws IOException, MerlinServiceException {
    try {
      //TODO: (mem optimization) use streams here to avoid several copies of strings
      final var reqBody = JsonNodeFactory.instance.objectNode();
      reqBody.put("query", query);
      reqBody.set("variables", variables);
      final var httpReq = HttpRequest
          .newBuilder().uri(merlinGraphqlURI).timeout(httpTimeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("Origin", merlinGraphqlURI.toString())
          .header("x-hasura-admin-secret", hasuraGraphQlAdminSecret)
          .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
          .build();
      //TODO: (net optimization) gzip compress the request body if large enough (eg for createAllActs)
      final var httpResp = HttpClient
          .newHttpClient().send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
      if (httpResp.statusCode() != 200) {
        //TODO: how severely to error out if aerie cannot be reached or has a 500 error or json is garbled etc etc?
        return Optional.empty();
      }
      final var mapper = new ObjectMapper();
      final var respBody = (ObjectNode) mapper.readTree(httpResp.body());
      if (respBody.has("errors")) {
        throw new MerlinServiceException(respBody.toString());
      }
      return Optional.of(respBody);
    } catch (final InterruptedException e) {
      //TODO: maybe retry if interrupted? but depends on semantics (eg don't duplicate mutation if not idempotent)
      return Optional.empty();
    }
  }

  //TODO: maybe use fancy aerie typed json parsers/serializers, ala BasicParsers.productP use in MerlinParsers
  //TODO: or upgrade to gson or similar modern library with registered object mappings

  /**
   * {@inheritDoc}
   */
  @Override
  public long getPlanRevision(final PlanId planId) throws IOException, NoSuchPlanException, MerlinServiceException {
    final var query = """
        query GetPlanRevision($id: Int!) {
          plan_by_pk(id: $id) {
            revision
          }
        }
        """;
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("id", planId.id());

    final var response = postRequest(query, variables).orElseThrow(() -> new NoSuchPlanException(planId));
    try {
      return response.get("data").get("plan_by_pk").get("revision").longValue();
    } catch (ClassCastException | ArithmeticException e) {
      throw new NoSuchPlanException(planId);
    }
  }

  /**
   * {@inheritDoc}
   *
   * retrieves the metadata via a single atomic graphql query
   */
  @Override
  public PlanMetadata getPlanMetadata(final PlanId planId)
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    final var request = (
        "query getPlanMetadata { "
        + "plan_by_pk( id: %s ) { "
        + "  id revision start_time duration "
        + "  mission_model { "
        + "    id name version "
        + "    uploaded_file { name } "
        + "  } "
        + "  simulations(limit:1, order_by:{revision:desc} ) { arguments }"
        + "} }"
    ).formatted(planId.id());
    final var response = postRequest(request).orElseThrow(() -> new NoSuchPlanException(planId));
    try {
      //TODO: elevate and then leverage existing MerlinParsers (after updating them to match current db!)
      final var plan = response.get("data").get("plan_by_pk");
      final long planPK = plan.get("id").longValue();
      final long planRev = plan.get("revision").longValue();
      final var startTime = parseGraphQLTimestamp(plan.get("start_time").textValue());
      final var duration = durationFromPGInterval(plan.get("duration").textValue());

      final var model = plan.get("mission_model");
      final var modelId = model.get("id").longValue();
      final var modelName = model.get("name").textValue();
      final var modelVersion = model.get("version").textValue();

      final var file = model.get("uploaded_file");
      final var modelPath = Path.of(file.get("name").textValue());
      //NB: not using the "path" field because it is just a hex-encoded duplicate of the name field anyway
      //NB: the name includes the .jar extension

      //TODO: how to know right model config for scheduling? for now choosing latest sim setup (see query above)
      var modelConfiguration = Map.<String, SerializedValue>of();
      final var sims = plan.get("simulations");
      if (!sims.isEmpty()) {
        final var args = sims.get(0).get("arguments");
        modelConfiguration = BasicParsers
            .mapP(serializedValueP).parse(args)
            .getSuccessOrThrow((reason) -> new InvalidJsonException(new InvalidEntityException(List.of(reason))));
      }

      final var endTime = startTime.toInstant().plusNanos(1000L * duration.in(MICROSECOND));
      final var horizon = new PlanningHorizon(startTime.toInstant(), endTime);

      return new PlanMetadata(
          new PlanId(planPK),
          planRev,
          horizon,
          modelId,
          modelPath,
          modelName,
          modelVersion,
          modelConfiguration);
    } catch (ClassCastException | ArithmeticException | InvalidJsonException e) {
      //TODO: better error reporting upward to service response (NSPEx doesn't allow passing e as cause)
      throw new NoSuchPlanException(planId);
    }
  }

  /**
   * {@inheritDoc}
   * @return
   */
  @Override
  public MerlinPlan getPlanActivityDirectives(final PlanMetadata planMetadata, final Problem problem)
  throws IOException, NoSuchPlanException, MerlinServiceException, InvalidJsonException, InstantiationException
  {
    final var merlinPlan = new MerlinPlan();
    final var request =
        "query { plan_by_pk(id:%d) { activity_directives { id start_offset type arguments anchor_id anchored_to_start } duration start_time }} ".formatted(
            planMetadata.planId().id());
    final var response = postRequest(request).orElseThrow(() -> new NoSuchPlanException(planMetadata.planId()));
    final var jsonplan = response.get("data").get("plan_by_pk");
    final var activityDirectives = jsonplan.get("activity_directives");
    for (int i = 0; i < activityDirectives.size(); i++) {
      final var jsonActivity = activityDirectives.get(i);
      final var type = activityDirectives.get(i).get("type").textValue();
      final var start = jsonActivity.get("start_offset").textValue();
      final Integer anchorId = jsonActivity.get("anchor_id").isNull() ? null : jsonActivity.get("anchor_id").intValue();
      final boolean anchoredToStart = jsonActivity.get("anchored_to_start").booleanValue();
      final var arguments = jsonActivity.get("arguments");
      final var deserializedArguments = BasicParsers
          .mapP(serializedValueP)
          .parse(arguments)
          .getSuccessOrThrow((reason) -> new InvalidJsonException(new InvalidEntityException(List.of(reason))));
      final var effectiveArguments = problem
          .getActivityType(type)
          .getSpecType()
          .getInputType()
          .getEffectiveArguments(deserializedArguments);
      final var merlinActivity = new ActivityDirective(
          durationFromPGInterval(start),
          type,
          effectiveArguments,
          (anchorId != null) ? new ActivityDirectiveId(anchorId) : null,
          anchoredToStart);
      final var actPK = new ActivityDirectiveId(jsonActivity.get("id").longValue());
      merlinPlan.addActivity(actPK, merlinActivity);
    }
    return merlinPlan;
  }

  /**
   * generate a name for the next created plan container using current timestamp
   *
   * currently, does not actually verify that the name is unique within aerie database
   *
   * @return a name for the next created plan container
   */
  public String getNextPlanName() {
    //TODO: (defensive) should rely on database to generate a new unique name to avoid user collisions
    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
    return "scheduled_plan_" + dtf.format(LocalDateTime.now());
  }

  /**
   * {@inheritDoc}
   * @return
   */
  @Override
  public Pair<PlanId, Map<ActivityDirectiveId, ActivityDirectiveId>> createNewPlanWithActivityDirectives(
      final PlanMetadata planMetadata,
      final Plan plan,
      final Map<SchedulingActivity, GoalId> activityToGoalId,
      final SchedulerModel schedulerModel
  )
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    final var planName = getNextPlanName();
    final var planId = createEmptyPlan(
        planName, planMetadata.modelId(),
        planMetadata.horizon().getStartInstant(), planMetadata.horizon().getEndAerie());
    final Map<ActivityDirectiveId, ActivityDirectiveId> activityToId = createAllPlanActivityDirectives(planId, plan, activityToGoalId, schedulerModel);

    return Pair.of(planId, activityToId);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PlanId createEmptyPlan(final String name, final long modelId, final Instant startTime, final Duration duration)
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    final var requestFormat = (
        "mutation createEmptyPlan { insert_plan_one( object: { "
        + "name: %s model_id: %d start_time: %s duration: %s "
        + "} ) { id } }");
    //TODO: resolve inconsistency in plan duration versus activity duration formats in merlin
    //NB: the duration format for creating plans is different than that for activity instances (microseconds)
    final var durStr = "\"" + duration.in(Duration.SECOND) + "\"";
    final var request = requestFormat.formatted(
        serializeForGql(name), modelId, serializeForGql(startTime.toString()), durStr);

    final var response = postRequest(request).orElseThrow(() -> new NoSuchPlanException(null));
    try {
      return new PlanId(
          response
              .get("data")
              .get("insert_plan_one")
              .get("id")
              .longValue());
    } catch (ClassCastException | ArithmeticException e) {
      throw new NoSuchPlanException(null);
    }
  }

  /**
   * {@inheritDoc}
   * @return
   */
  @Override
  public Map<ActivityDirectiveId, ActivityDirectiveId> updatePlanActivityDirectives(
      final PlanId planId,
      final MerlinPlan initialPlan,
      final Plan plan,
      final Map<SchedulingActivity, GoalId> activityToGoalId,
      final SchedulerModel schedulerModel
      )
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    final var ids = new HashMap<ActivityDirectiveId, ActivityDirectiveId>();
    //creation are done in batch as that's what the scheduler does the most
    final var toAdd = new ArrayList<SchedulingActivity>();
    final var toDelete = new ArrayList<ActivityDirectiveId>();
    final var toModify = new ArrayList<ActivityModification>();
    for (final var activity : plan.getActivities()) {
      if(activity.getParentActivity().isPresent()) continue; // Skip generated activities
      final var actFromInitialPlanOptional = initialPlan.getActivityById(activity.id());
      if (actFromInitialPlanOptional.isPresent()) {
        final var actFromInitialPlan = actFromInitialPlanOptional.get();
        //if act was present in initial plan
        final var activityDirectiveFromSchedulingDirective = new ActivityDirective(
            activity.startOffset(),
            activity.type().getName(),
            activity.arguments(),
            activity.anchorId(),
            activity.anchoredToStart()
        );
        if (!activityDirectiveFromSchedulingDirective.equals(actFromInitialPlan)) {
          final var ops = generateModification(actFromInitialPlan, activityDirectiveFromSchedulingDirective);
          if (!ops.isEmpty()) toModify.add(new ActivityModification(activity.id(), ops));
        }
        ids.put(activity.id(), activity.id());
      } else {
        //act was not present in initial plan, create new activity
        toAdd.add(activity);
      }
    }
    final var actsFromNewPlan = plan.getActivitiesById();
    for (final var idInInitialPlan : initialPlan.getActivitiesById().keySet()) {
      if (!actsFromNewPlan.containsKey(idInInitialPlan)) {
        toDelete.add(idInInitialPlan);
      }
    }

    //Create
    ids.putAll(createActivityDirectives(planId, toAdd, activityToGoalId, schedulerModel));

    // Create does not upload the anchor ids, because directive IDs can change during upload
    // and it would cause a foreign key violation. So we map the anchor ids using the creation results
    // and add an anchor-modification entry to the `toModify` list after the fact.
    for (final var act: toAdd) {
      if (act.anchorId() != null) {
        toModify.add(new ActivityModification(
            ids.get(act.id()),
            List.of($ -> $.put("anchor_id", ids.get(act.anchorId()).id()))
        ));
      }
    }

    modifyActivityDirectives(planId, toModify);
    deleteActivityDirectives(planId, toDelete);
    return ids;
  }

  /**
   * Generates the list of operations needed to change an activity in the database.
   *
   * @param oldState the old activity before modification
   * @param newState the modified activity
   */
  private List<ActivityOperation> generateModification(final ActivityDirective oldState, final ActivityDirective newState) {
    final var operations = new ArrayList<ActivityOperation>();

    if (!Objects.equals(newState.serializedActivity().getTypeName(), oldState.serializedActivity().getTypeName())) {
      throw new IllegalStateException(
          "Modified activities cannot change type. Was " + oldState.serializedActivity().getTypeName()
          + ", now " + newState.serializedActivity().getTypeName()
      );
    }
    if (!Objects.equals(newState.serializedActivity().getArguments(), oldState.serializedActivity().getArguments())) {
      throw new IllegalStateException(
          "Modified activities cannot change arguments. Was " + oldState.serializedActivity().getArguments()
          + ", now " + newState.serializedActivity().getArguments()
      );
    }

    if (newState.startOffset() != oldState.startOffset()) {
      operations.add(
          $ -> $.put("start_offset", newState.startOffset().toString())
      );
    }

    if (newState.anchorId() != oldState.anchorId()) {
      if (newState.anchorId() != null) {
        operations.add(
            $ -> $.put("anchor_id", newState.anchorId().id())
        );
      } else {
        operations.add(
            $ -> $.set("anchor_id", NullNode.getInstance())
        );
      }
    }

    if (newState.anchoredToStart() != oldState.anchoredToStart()) {
      operations.add(
          $ -> $.put("anchor_id", newState.anchoredToStart())
      );
    }

    return operations;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void ensurePlanExists(final PlanId planId) throws IOException, NoSuchPlanException, MerlinServiceException {
    final Supplier<NoSuchPlanException> exceptionFactory = () -> new NoSuchPlanException(planId);
    final var request = "query ensurePlanExists { plan_by_pk( id: %s ) { id } }"
        .formatted(planId.id());
    final var response = postRequest(request).orElseThrow(exceptionFactory);
    try {
      final var id =
          new PlanId(
              response
              .get("data")
              .get("plan_by_pk")
              .get("id")
              .longValue());
      if (!id.equals(planId)) {
        throw exceptionFactory.get();
      }
    } catch (ClassCastException | ArithmeticException e) {
      //TODO: better error reporting upward to service response (NSPEx doesn't allow passing e as cause)
      throw exceptionFactory.get();
    }
  }

  /**
   * {@inheritDoc}
   */
  //TODO: (error cleanup) more diverse exceptions for failed operations
  @Override
  public void clearPlanActivityDirectives(final PlanId planId) throws IOException, NoSuchPlanException,
                                                                      MerlinServiceException
  {
    ensurePlanExists(planId);
    final var request = (
        "mutation clearPlanActivities {"
        + "  delete_activity_directive(where: { plan_id: { _eq: %d } }) {"
        + "    affected_rows"
        + "  }"
        + "}"
    ).formatted(planId.id());
    final var response = postRequest(request).orElseThrow(() -> new NoSuchPlanException(planId));
    try {
      response.get("data").get("delete_activity").get("affected_rows").longValue();
    } catch (ClassCastException | ArithmeticException e) {
      throw new NoSuchPlanException(planId);
    }
  }

  /**
   * {@inheritDoc}
   * @return
   */
  @Override
  public Map<ActivityDirectiveId, ActivityDirectiveId> createAllPlanActivityDirectives(
      final PlanId planId,
      final Plan plan,
      final Map<SchedulingActivity, GoalId> activityToGoalId,
      final SchedulerModel schedulerModel
  )
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    return createActivityDirectives(planId, plan.getActivitiesByTime(), activityToGoalId, schedulerModel);
  }

  private Map<ActivityDirectiveId, ActivityDirectiveId> createActivityDirectives(
      final PlanId planId,
      final List<SchedulingActivity> orderedActivities,
      final Map<SchedulingActivity, GoalId> activityToGoalId,
      final SchedulerModel schedulerModel
  )
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    ensurePlanExists(planId);
    final var query = """
        mutation createAllPlanActivityDirectives($activities: [activity_directive_insert_input!]!) {
          insert_activity_directive(objects: $activities) {
            returning {
              id
            }
            affected_rows
          }
        }
        """;

    //assemble the entire mutation request body
    //TODO: (optimization) could use a lazy evaluating stream of strings to avoid large set of strings in memory
    //TODO: (defensive) should sanitize all strings uses as keys/values to avoid injection attacks

    final var insertionObjects = JsonNodeFactory.instance.arrayNode();
    for (final var act : orderedActivities) {
      final var insertionObject = JsonNodeFactory.instance.objectNode();
      insertionObject.put("plan_id", planId.id());
      insertionObject.put("type", act.getType().getName());
      insertionObject.put("start_offset", act.startOffset().toString());
      insertionObject.put("anchored_to_start", act.anchoredToStart());

      if (act.name() != null) insertionObject.put("name", act.name());

      //add duration to parameters if controllable
      final var insertionObjectArguments = JsonNodeFactory.instance.objectNode();
      if(act.getType().getDurationType() instanceof DurationType.Controllable(String parameterName)){
        if(!act.arguments().containsKey(parameterName)){
          insertionObjectArguments.set(parameterName, JsonEncoding.encode(schedulerModel.serializeDuration(act.duration())));
        }
      }

      final var goalId = activityToGoalId.get(act);
      if (goalId != null) {
        insertionObject.put("source_scheduling_goal_id", goalId.id());
        goalId.goalInvocationId().ifPresent($ -> insertionObject.put("source_scheduling_goal_invocation_id", $));
      }

      for (final var arg : act.arguments().entrySet()) {
        insertionObjectArguments.set(arg.getKey(), JsonEncoding.encode(arg.getValue()));
      }
      insertionObject.set("arguments", insertionObjectArguments);
      insertionObjects.add(insertionObject);
    }

    final var arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("activities", insertionObjects);

    final var response = postRequest(query, arguments).orElseThrow(() -> new NoSuchPlanException(planId));

    final Map<ActivityDirectiveId, ActivityDirectiveId> activityToDirectiveId = new HashMap<>();
    try {
      final var numCreated = response
          .get("data").get("insert_activity_directive").get("affected_rows").longValue();
      if (numCreated != orderedActivities.size()) {
        throw new NoSuchPlanException(planId);
      }
      var ids = response
          .get("data").get("insert_activity_directive").get("returning");
      //make sure we associate the right id with the right activity
      for(int i = 0; i < ids.size(); i++) {
        final var newId = new ActivityDirectiveId(ids.get(i).get("id").intValue());
        activityToDirectiveId.put(orderedActivities.get(i).id(), newId);
      }
    } catch (ClassCastException | ArithmeticException e) {
      throw new NoSuchPlanException(planId);
    }
    return activityToDirectiveId;
  }

  private record ActivityModification(
      ActivityDirectiveId id,
      List<ActivityOperation> operations
  ) {}

  interface ActivityOperation {
    void apply(ObjectNode obj);
  }

  private void modifyActivityDirectives(
      final PlanId planId,
      final List<ActivityModification> modifications
  )
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    if (modifications.isEmpty()) return;
    ensurePlanExists(planId);
    final var request = new StringBuilder();
    request.append("mutation updatePlanActivityDirectives(");
    request.append(String.join(
        ",",
        modifications.stream().map($ -> "$activity_%d: activity_directive_set_input!".formatted($.id().id())).toList()
    ));
    request.append(") {");
    final var arguments = JsonNodeFactory.instance.objectNode();
    for (final var mod : modifications) {
      final var id = mod.id().id();
      request.append("""
                         update_%d: update_activity_directive_by_pk(pk_columns: {id: %d, plan_id: %d}, _set: $activity_%d) {
                          id
                         }
                         """.formatted(id, id, planId.id(), id));

      final var activityObject = JsonNodeFactory.instance.objectNode();
      mod.operations.forEach($ -> $.apply(activityObject));

      arguments.set("activity_%d".formatted(id), activityObject);
    }
    request.append("}");
    postRequest(request.toString(), arguments).orElseThrow(() -> new NoSuchPlanException(planId));
  }

  private void deleteActivityDirectives(
      final PlanId planId,
      final List<ActivityDirectiveId> ids
  )
  throws IOException, NoSuchPlanException, MerlinServiceException
  {
    if (ids.isEmpty()) return;
    ensurePlanExists(planId);
    final var idString = ids.stream().map($ -> String.valueOf($.id())).collect(Collectors.joining(","));
    final var request = """
        mutation deletePlanActivityDirectives($planId: Int! = %d, $directiveIds: [Int!]! = [%s]) {
          delete_activity_directive(where: {_and: {plan_id: {_eq: $planId}, id: {_in: $directiveIds}}}) {
            affected_rows
          }
        }
        """.formatted(planId.id(), idString);
    postRequest(request).orElseThrow(() -> new NoSuchPlanException(planId));
  }


  @Override
  public MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final PlanId planId)
  throws IOException, MerlinServiceException
  {
    final var request = """
        query GetActivityTypesForPlan {
          plan_by_pk(id: %d) {
            mission_model {
              id
              activity_types {
                name
                parameters
                presets {
                  name
                  arguments
                }
              }
            }
          }
        }
        """.formatted(planId.id());
    final ObjectNode response;
    response = postRequest(request).get();

    final var activityTypesJsonArray =
        response.get("data")
                .get("plan_by_pk")
                .get("mission_model")
                .get("activity_types");
    final var activityTypes = parseActivityTypes(activityTypesJsonArray);

    final var missionModelId = new MissionModelId(response.get("data")
                                       .get("plan_by_pk")
                                       .get("mission_model")
                                       .get("id").intValue());

    return new MerlinDatabaseService.MissionModelTypes(activityTypes, getResourceTypes(missionModelId));
  }

  private static List<ActivityType> parseActivityTypes(final JsonNode activityTypesJsonArray) {
    final var activityTypes = new ArrayList<ActivityType>();
    for (final var activityTypeJson : activityTypesJsonArray) {
      final var parametersJson = activityTypeJson.get("parameters");
      final var parameters = new HashMap<String, ValueSchema>();
      final var parameterFields = parametersJson.fields();
      while (parameterFields.hasNext()) {
        final var parameterJson = parameterFields.next();
        parameters.put(
            parameterJson.getKey(),
            valueSchemaP
                .parse(parameterJson.getValue().get("schema"))
                .getSuccessOrThrow());
      }
      final var presetsJsonArray = activityTypeJson.get("presets");
      final var presets = new HashMap<String, Map<String, SerializedValue>>();
      for (final var presetJson: presetsJsonArray) {
        final var argumentsJson = presetJson.get("arguments");
        final var arguments = new HashMap<String, SerializedValue>();
        final var argumentFields = argumentsJson.fields();
        while (argumentFields.hasNext()) {
          final var argumentJson = argumentFields.next();
          arguments.put(
              argumentJson.getKey(),
              JsonEncoding.decode(argumentJson.getValue())
          );
        }
        presets.put(
            presetJson.get("name").textValue(),
            arguments
        );
      }
      activityTypes.add(new ActivityType(activityTypeJson.get("name").textValue(), parameters, presets));
    }
    return activityTypes;
  }

  @Override
  public MerlinDatabaseService.MissionModelTypes getMissionModelTypes(final MissionModelId missionModelId)
  throws IOException, NoSuchMissionModelException, MerlinServiceException
  {
    final var request = """
        query GetActivityTypesFromMissionModel{
          mission_model_by_pk(id:%d){
            activity_types{
              name
              parameters
              presets {
                name
                arguments
              }
            }
          }
        }
        """.formatted(missionModelId.id());
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data");
    if (data.get("mission_model_by_pk").isNull()) throw new NoSuchMissionModelException(missionModelId);
    final var activityTypesJsonArray = data
        .get("mission_model_by_pk")
        .get("activity_types");
    final var activityTypes = parseActivityTypes(activityTypesJsonArray);

    return new MerlinDatabaseService.MissionModelTypes(activityTypes, getResourceTypes(missionModelId));
  }

  public Collection<ResourceType> getResourceTypes(final MissionModelId missionModelId)
  throws IOException, MerlinServiceException
  {
    final var request = """
        query GetResourceTypes {
           resource_type(where: {model_id: {_eq: %d}}) {
             name
             schema
           }
         }
        """.formatted(missionModelId.id());
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data");
    final var resourceTypesJsonArray = data.get("resource_type");

    final var resourceTypes = new ArrayList<ResourceType>();

    for (final var jsonValue : resourceTypesJsonArray) {
      final var name = jsonValue.get("name").textValue();
      final var schema = jsonValue.get("schema");

      resourceTypes.add(new ResourceType(name, valueSchemaP.parse(schema).getSuccessOrThrow()));
    }

    return resourceTypes;
  }

  /**
   * Gets resource types associated to a plan, those coming from the mission model as well as those coming from external dataset resources
   * @param planId the plan id
   * @return
   * @throws IOException
   * @throws MerlinServiceException
   * @throws NoSuchPlanException
   */
  @Override
  public Collection<ResourceType> getResourceTypes(final PlanId planId)
  throws IOException, MerlinServiceException, NoSuchPlanException
  {
    final var missionModelId = this.getPlanMetadata(planId).modelId();
    final var missionModelResourceTypes = getResourceTypes(new MissionModelId(missionModelId));
    final var allResourceTypes = new ArrayList<>(missionModelResourceTypes);
    final var associatedDataset = getExternalDatasets(planId);
    if(associatedDataset.isPresent()) {
      for(final var datasetMetada: associatedDataset.get()) {
        final var profileSet = getProfileTypes(datasetMetada.datasetId());
        allResourceTypes.addAll(extractResourceTypes(profileSet));
      }
    }
    return allResourceTypes;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<ActivityDirectiveId, GoalId> getActivityIdToGoalIdMap(final PlanId planId)
  throws MerlinServiceException, IOException
  {
    final var request = """
        query {
          activity_directive(where: {plan_id: {_eq: %d}}) {
            id
            source_scheduling_goal_id
            source_scheduling_goal_invocation_id
          }
        }
        """.formatted(planId.id());
    final ObjectNode response = postRequest(request).get();
    final var data = response.get("data");
    final var activityDirectiveArray = data.get("activity_directive");
    final var results = new ArrayList<Map.Entry<ActivityDirectiveId, GoalId>>();
    for (final var $ : activityDirectiveArray) {
      final var id = new ActivityDirectiveId($.get("id").intValue());
      if ($.get("source_scheduling_goal_id").isNull()) { results.add(null); continue; }
      final var source_goal = $.get("source_scheduling_goal_id").intValue();
      if ($.get("source_scheduling_goal_invocation_id").isNull()) { results.add(entry(id, new GoalId(source_goal, -1, Optional.empty()))); continue; }
      final Long source_invocation = (long) $.get("source_scheduling_goal_invocation_id").intValue();
      results.add(entry(id, new GoalId(source_goal, -1, Optional.of(source_invocation))));
    }
    return Map.ofEntries(results.stream().filter(Objects::nonNull).toArray(Map.Entry[]::new));
  }

  public SimulationId getSimulationId(PlanId planId) throws MerlinServiceException, IOException {
    final var request = """
        query {
          simulation(where: {plan_id: {_eq: %d}}) {
            id
          }
        }
        """.formatted(planId.id());
  final ObjectNode response;
  response = postRequest(request).get();
  final var data = response.get("data");
  final var simulationId = data.get("simulation").get(0).get("id").intValue();
  return new SimulationId(simulationId);
}

  @Override
  public DatasetId storeSimulationResults(
      final PlanMetadata planMetadata,
      final SimulationResults results,
      final Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap
  ) throws MerlinServiceException, IOException {
    final var simulationId = getSimulationId(planMetadata.planId());
    final var datasetIds = createSimulationDataset(simulationId, planMetadata);
    final var profileSet = ProfileSet.of(results.realProfiles, results.discreteProfiles);
    final var profileRecords = postResourceProfiles(
        datasetIds.datasetId(),
        profileSet.realProfiles(),
        profileSet.discreteProfiles());
    postProfileSegments(datasetIds.datasetId(), profileRecords, profileSet);
    postActivities(datasetIds.datasetId(), results.simulatedActivities, results.unfinishedActivities, results.startTime, uploadIdMap);
    insertSimulationTopics(datasetIds.datasetId(), results.topics);
    insertSimulationEvents(datasetIds.datasetId(), results.events);
    setSimulationDatasetStatus(datasetIds.simulationDatasetId(), SimulationStateRecord.success());
    return datasetIds.datasetId();
  }

  private Map<ActivityInstanceId, ActivityInstance> getSimulatedActivities(SimulationDatasetId datasetId, Instant startSimulation)
  throws MerlinServiceException, IOException, InvalidJsonException
  {
    final var request = """
        query{
          simulated_activity(where: {simulation_dataset_id: {_eq: %d}}) {
            activity_directive {
              id
              arguments
              type
              anchored_to_start
              anchor_id
            }
            activity_type_name
            duration
            id
            parent_id
            start_offset
            attributes
          }
        }
        """.formatted(datasetId.id());
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data").get("simulated_activity");
    return parseSimulatedActivities(data, startSimulation);
  }

  private ProfileSet getProfileTypes(DatasetId datasetId) throws MerlinServiceException, IOException {
    final var request = """
        query{
          profile(where: {dataset_id: {_eq: %d}}){
            type
            name
          }
        }
        """.formatted(datasetId.id());
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data").get("profile");
    return parseProfiles(data);
  }

  private ProfileSet getProfilesWithSegments(DatasetId datasetId) throws MerlinServiceException, IOException {
    final var request = """
        query{
          profile(where: {dataset_id: {_eq: %d}}){
            type
            duration
            profile_segments {
              start_offset
              dynamics
              is_gap
            }
            name
          }
        }
        """.formatted(datasetId.id());
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data").get("profile");
    return parseProfiles(data);
  }

  private Map<ActivityInstanceId, UnfinishedActivity> getSpans(DatasetId datasetId, Instant startTime) throws
                                                                                                        MerlinServiceException, IOException {
    final var request = """
       query{
       span(where: {duration: {_is_null: true}, dataset_id: {_eq: %d}}) {
              attributes
              parent_id
              type
              start_offset
              span_id
            }
            }
        """.formatted(datasetId.id());
  final ObjectNode response;
  response = postRequest(request).get();
  final var data = response.get("data").get("span");
  return parseUnfinishedActivities(data, startTime);
  }

  @Override
  public Optional<Pair<SimulationResults, DatasetId>> getSimulationResults(PlanMetadata planMetadata)
  throws MerlinServiceException, IOException
  {
    final var simulationDatasetId = getSuitableSimulationResults(planMetadata);
    if(simulationDatasetId.isEmpty()) return Optional.empty();
    try(var executorService = Executors.newFixedThreadPool(3)) {
      Future<Map<ActivityInstanceId, ActivityInstance>> futureSimulatedActivities = executorService.submit(() -> getSimulatedActivities(
          simulationDatasetId.get().simulationDatasetId(),
          planMetadata.horizon().getStartInstant()));
      Future<Map<ActivityInstanceId, UnfinishedActivity>> futureSpans = executorService.submit(() -> getSpans(
          simulationDatasetId.get().datasetId(),
          planMetadata.horizon().getStartInstant()));
      Future<ProfileSet> futureProfiles = executorService.submit(() -> getProfilesWithSegments(simulationDatasetId.get().datasetId()));
      try {
        final var simulatedActivities = futureSimulatedActivities.get();
        final var unfinishedActivities = futureSpans.get();
        final var profiles = futureProfiles.get();
        //verify that there is no gap and convert
        final var unwrappedProfiles = unwrapProfiles(profiles);
        final var simulationStartTime = planMetadata.horizon().getStartInstant();
        final var simulationEndTime = planMetadata.horizon().getEndInstant();
        final var micros = java.time.Duration.between(simulationStartTime, simulationEndTime).toNanos() / 1000;
        final var duration = Duration.of(micros, MICROSECOND);
        return Optional.of(Pair.of(new SimulationResults(
            unwrappedProfiles.realProfiles(),
            unwrappedProfiles.discreteProfiles(),
            simulatedActivities,
            unfinishedActivities,
            simulationStartTime,
            duration,
            List.of(),
            new TreeMap<>()
        ), simulationDatasetId.get().datasetId));
      } catch (InterruptedException | ExecutionException e) {
        return Optional.empty();
      }
    }
  }

  public Optional<List<DatasetMetadata>> getExternalDatasets(final PlanId planId)
  throws MerlinServiceException, IOException
  {
    final var datasets = new ArrayList<DatasetMetadata>();
    final var request = """
        query {
          plan_dataset(where: {plan_id: {_eq: %d}, simulation_dataset_id: {_is_null: true}}, order_by: {dataset_id:asc}) {
            dataset_id
            offset_from_plan_start
          }
        }
        """.formatted(planId.id());
    final var response = postRequest(request).get();
    final var data = response.get("data").get("plan_dataset");
    if (data.size() == 0) {
      return Optional.empty();
    }
    for(final var dataset:data){
      final var datasetId = new DatasetId(dataset.get("dataset_id").intValue());
      final var offsetFromPlanStart = durationFromPGInterval(dataset.get("offset_from_plan_start").textValue());
      datasets.add(new DatasetMetadata(datasetId, offsetFromPlanStart));
    }
    return Optional.of(datasets);
  }

  @Override
  public ExternalProfiles getExternalProfiles(final PlanId planId)
  throws MerlinServiceException, IOException {
    final Map<String, LinearProfile> realProfiles = new HashMap<>();
    final Map<String, DiscreteProfile> discreteProfiles = new HashMap<>();
    final var resourceTypes = new ArrayList<ResourceType>();
    final var datasetMetadatas = getExternalDatasets(planId);
    if(datasetMetadatas.isPresent()) {
      for(final var datasetMetadata: datasetMetadatas.get()) {
        final var profiles = getProfilesWithSegments(datasetMetadata.datasetId());
        profiles.realProfiles().forEach((name, profile) -> {
          realProfiles.put(name,
                           LinearProfile.fromExternalProfile(
                               datasetMetadata.offsetFromPlanStart,
                               profile.segments()));
        });
        profiles.discreteProfiles().forEach((name, profile) -> {
          discreteProfiles.put(name,
                               DiscreteProfile.fromExternalProfile(
                                   datasetMetadata.offsetFromPlanStart,
                                   profile.segments()));
        });
        resourceTypes.addAll(extractResourceTypes(profiles));
      }
    }
    return new ExternalProfiles(realProfiles, discreteProfiles, resourceTypes);
  }

  @Override
  public Map<String, List<ExternalEvent>> getExternalEvents(final PlanId planId, final Instant horizonStart)
  throws MerlinServiceException, IOException, InvalidEntityException
  {
    final var derivationGroupsRequest = """
        query DerivationGroupsForPlan($planId: Int!) {
          plan_derivation_group(where: {plan_id: {_eq: $planId}}) {
            derivation_group_name
          }
        }
        """;
    final var planIdVar = JsonNodeFactory.instance.objectNode();
    planIdVar.put("planId", planId.id());
    final ObjectNode derivationGroupsResponse = postRequest(
        derivationGroupsRequest,
        planIdVar).get();
    final var derivationGroupNames = JsonNodeFactory.instance.arrayNode();
    for (final var $ : derivationGroupsResponse.get("data").get("plan_derivation_group")) {
      derivationGroupNames.add($.get("derivation_group_name").textValue());
    }
    final var derivationGroups = JsonNodeFactory.instance.objectNode();
    derivationGroups.set("derivationGroups", derivationGroupNames);

    final var eventsRequest = """
        query DerivedEventsForPlan($derivationGroups: [String!]!) {
          derived_events(where: {derivation_group_name: {_in: $derivationGroups}}) {
            attributes
            source_key
            event_type_name
            event_key
            duration
            derivation_group_name
            source_range
            start_time
            valid_at
            external_source {
              attributes
            }
          }
        }""";

    final ObjectNode eventsResponse = postRequest(eventsRequest, derivationGroups).get();

    final var data = eventsResponse.get("data").get("derived_events");
    final var unorganized =  parseExternalEvents(data, horizonStart);
    final var result = new HashMap<String, List<ExternalEvent>>();
    for (final var event: unorganized) {
      final var list = result.computeIfAbsent(event.source.derivationGroup, $ -> new ArrayList<>());
      list.add(event);
    }
    return result;
  }

  private Collection<ResourceType> extractResourceTypes(final ProfileSet profileSet){
    final var resourceTypes = new ArrayList<ResourceType>();
    profileSet.realProfiles().forEach((name, profile) -> {
      resourceTypes.add(new ResourceType(name, profile.schema()));
    });
    profileSet.discreteProfiles().forEach((name, profile) -> {
      resourceTypes.add(new ResourceType(name, profile.schema()));
    });
    return resourceTypes;
  }

  private Map<ActivityInstanceId, UnfinishedActivity> parseUnfinishedActivities(JsonNode unfinishedActivitiesJson, Instant simulationStart){
    final var unfinishedActivities = new HashMap<ActivityInstanceId, UnfinishedActivity>();
    for(final var unfinishedActivityJson: unfinishedActivitiesJson){
      final var activityAttributes = activityAttributesP.parse(unfinishedActivityJson.get("attributes")).getSuccessOrThrow();
      ActivityInstanceId parentId = null;
      if(!unfinishedActivityJson.get("parent_id").isNull()){
        parentId = new ActivityInstanceId(unfinishedActivityJson.get("parent_id").longValue());
      }
      final var activityType = unfinishedActivityJson.get("type").textValue();
      final var start = instantFromStart(simulationStart,
          durationFromPGInterval(unfinishedActivityJson.get("start_offset").textValue()));
      final var id = new ActivityInstanceId(unfinishedActivityJson.get("id").longValue());
      Optional<ActivityDirectiveId> actDirectiveId = Optional.empty();
      if(activityAttributes.directiveId().isPresent()){
        actDirectiveId = Optional.of(new ActivityDirectiveId(activityAttributes.directiveId().get()));
      }
      final var unfinishedActivity = new UnfinishedActivity(
          activityType,
          activityAttributes.arguments(),
          start,
          parentId,
          List.of(),
          actDirectiveId
      );
      unfinishedActivities.put(id, unfinishedActivity);
    }
    return unfinishedActivities;
  }

  private UnwrappedProfileSet unwrapProfiles(final ProfileSet profileSet) throws MerlinServiceException {
    return new UnwrappedProfileSet(unwrapProfiles(profileSet.realProfiles()), unwrapProfiles(profileSet.discreteProfiles()));
  }

  private <Dynamics> HashMap<String, ResourceProfile<Dynamics>> unwrapProfiles(
      Map<String,ResourceProfile<Optional<Dynamics>>> profiles
  ) {
    final var unwrapped = new HashMap<String, ResourceProfile<Dynamics>>();
    for(final var profile: profiles.entrySet()) {
      final var unwrappedSegments = new ArrayList<ProfileSegment<Dynamics>>();
      for (final var segment : profile.getValue().segments()) {
        if (segment.dynamics().isPresent()) {
          unwrappedSegments.add(new ProfileSegment<>(segment.extent(), segment.dynamics().get()));
        }
      }
      unwrapped.put(profile.getKey(), ResourceProfile.of(profile.getValue().schema(), unwrappedSegments));
    }
    return unwrapped;
  }

  private ProfileSet parseProfiles(JsonNode dataset){
    Map<String, ResourceProfile<Optional<RealDynamics>>> realProfiles = new HashMap<>();
    Map<String, ResourceProfile<Optional<SerializedValue>>> discreteProfiles = new HashMap<>();
    for(final var profile :dataset){
      final var name = profile.get("name").textValue();
      final var type = profile.get("type");
      final var typetype = type.get("type").textValue();
      final boolean isReal = typetype.equals("real");
      if(isReal){
        final var realProfile = parseProfile(profile, realDynamicsP);
        realProfiles.put(name, realProfile);
      } else {
        final var discreteProfile = parseProfile(profile, serializedValueP);
        discreteProfiles.put(name, discreteProfile);
      }
    }
    return new ProfileSet(realProfiles, discreteProfiles);
  }

  private <Dynamics> ResourceProfile<Optional<Dynamics>> parseProfile(JsonNode profile, JsonParser<Dynamics> dynamicsParser){
    // Profile segments are stored with their start offset relative to simulation start
    // We must convert these to durations describing how long each segment lasts
    final var type = chooseP(discreteValueSchemaTypeP, realValueSchemaTypeP).parse(profile.get("type")).getSuccessOrThrow();
    final var segments = new ArrayList<ProfileSegment<Optional<Dynamics>>>();
    if(profile.has("profile_segments")) {
      final var resultSet = profile.get("profile_segments").iterator();
      JsonNode curProfileSegment = null;
      if (resultSet.hasNext()) {
        final var profileExtent = durationFromPGInterval(profile.get("duration").textValue());
        curProfileSegment = resultSet.next();
        var offset = durationFromPGInterval(curProfileSegment.get("start_offset").textValue());
        var isGap = curProfileSegment.get("is_gap").booleanValue();
        Optional<Dynamics> dynamics;
        if (!isGap) {
          dynamics = Optional.of(dynamicsParser
                                     .parse(curProfileSegment.get("dynamics"))
                                     .getSuccessOrThrow());
        } else {
          dynamics = Optional.empty();
        }

        while (resultSet.hasNext()) {
          curProfileSegment = resultSet.next();
          final var nextOffset = durationFromPGInterval(curProfileSegment.get("start_offset").textValue());
          final var duration = nextOffset.minus(offset);
          segments.add(new ProfileSegment<>(duration, dynamics));
          isGap = curProfileSegment.get("is_gap").booleanValue();
          offset = nextOffset;
          if (!isGap) {
            dynamics = Optional.of(dynamicsParser
                                       .parse(curProfileSegment.get("dynamics"))
                                       .getSuccessOrThrow());
          } else {
            dynamics = Optional.empty();
          }
        }

        final var duration = profileExtent.minus(offset);
        segments.add(new ProfileSegment<>(duration, dynamics));
      }
    }
    return ResourceProfile.of(type, segments);
  }

  private List<ExternalEvent> parseExternalEvents(final JsonNode eventsJson, final Instant horizonStart)
  throws InvalidEntityException
  {
    final var result = new ArrayList<ExternalEvent>();
    for (final var eventJson : eventsJson) {
      final var start = new Duration(
          horizonStart.until(ZonedDateTime.parse(eventJson.get("start_time").textValue()).toInstant(), ChronoUnit.MICROS)
      );
      final var end = start.plus(Duration.fromString(eventJson.get("duration").textValue()));

      final var eventAttributes = new SerializedValueJsonParser()
          .parse(eventJson.get("attributes"))
          .getSuccessOrThrow(reason -> new InvalidEntityException(List.of(reason)))
          .asMap()
          .get();

      final var sourceAttributes = new SerializedValueJsonParser()
          .parse(eventJson.get("external_source").get("attributes"))
          .getSuccessOrThrow(reason -> new InvalidEntityException(List.of(reason)))
          .asMap()
          .get();

      result.add(new ExternalEvent(
          eventJson.get("event_key").textValue(),
          eventJson.get("event_type_name").textValue(),
          new ExternalSource(
              eventJson.get("source_key").textValue(),
              eventJson.get("derivation_group_name").textValue(),
              sourceAttributes
          ),
          eventAttributes,
          Interval.between(start, end)
      ));
    }
    return result;
  }

  private Map<ActivityInstanceId, ActivityInstance> parseSimulatedActivities(JsonNode simulatedActivitiesArray, Instant simulationStart)
  throws InvalidJsonException
  {
    final var simulatedActivities = new HashMap<ActivityInstanceId, ActivityInstance>();
    for(final var simulatedActivityJson: simulatedActivitiesArray) {
      //if no duration, this is an unfinished activity
      if(simulatedActivityJson.get("duration").isNull()) continue;
      final var activityDuration = GraphQLParsers.durationP.parse(simulatedActivityJson.get("duration")).getSuccessOrThrow();
      final var activityId = simulatedActivityJson.get("id").longValue();
      ActivityInstanceId parentId = null;
      if(!simulatedActivityJson.get("parent_id").isNull()){
        parentId = new ActivityInstanceId(simulatedActivityJson.get("parent_id").longValue());
      }
      final var startOffset = instantFromStart(simulationStart,durationFromPGInterval(simulatedActivityJson.get("start_offset").textValue()));
      final var computedAttributes = JsonEncoding.decode(simulatedActivityJson.get("attributes"));
      final var activityDirective = simulatedActivityJson.get("activity_directive");
      final var activityDirectiveId = new ActivityDirectiveId(activityDirective.get("id").intValue());
      final var activityDirectiveArguments = activityDirective.get("arguments");
      final var deserializedArguments = BasicParsers
          .mapP(serializedValueP)
          .parse(activityDirectiveArguments)
          .getSuccessOrThrow((reason) -> new InvalidJsonException(new InvalidEntityException(List.of(reason))));
      final var activityType = activityDirective.get("type").textValue();
      final var simulatedActivity = new ActivityInstance(
          activityType,
          deserializedArguments,
          startOffset,
          activityDuration,
          parentId,
          List.of(),
          Optional.of(activityDirectiveId),
          computedAttributes
      );
      simulatedActivities.put(new ActivityInstanceId(activityId), simulatedActivity);
    }
    return simulatedActivities;
  }

  /**
   * Returns the simulation dataset id if the simulation
   * - covers the entire planning horizon
   * - corresponds to the plan revision
   * @param planMetadata the plan metadata containing the planning horizon and plan revision
   * @return optionally a simulation dataset id
   */
  public Optional<DatasetIds> getSuitableSimulationResults(PlanMetadata planMetadata) throws MerlinServiceException, IOException {
    final var request =
        """
        {
          simulation_dataset(
            where: {
              status: {_eq: "success"},
              plan_revision: {_eq: %d},
              simulation_start_time: {_eq: "%s"},
              simulation_end_time: {_eq: "%s"},
              simulation: {plan_id: {_eq: %d}}
            }) {
              id
              dataset_id
              arguments
              simulation {
                arguments
              }
            }
        }""".formatted(
            planMetadata.planRev(),
            planMetadata.horizon().getStartInstant(),
            planMetadata.horizon().getEndInstant(),
            planMetadata.planId().id());
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data");
    final var simulationDatasets = data.get("simulation_dataset");
    for(final var simulationDataset  : simulationDatasets){
      final var simulationDatasetId = simulationDataset.get("id").intValue();
      final var datasetId = simulationDataset.get("dataset_id").intValue();
      final var simulationDatasetArguments = simulationArgumentsP.parse(simulationDataset.get("arguments")).getSuccessOrThrow();
      final var simulationArguments = simulationArgumentsP.parse(simulationDataset.get("simulation").get("arguments")).getSuccessOrThrow();
      if(!simulationDatasetArguments.equals(simulationArguments)) continue;
      return Optional.of(new DatasetIds(new DatasetId(datasetId), new SimulationDatasetId(simulationDatasetId)));
    }
    return Optional.empty();
  }

  private SimulationId createSimulation(final PlanId planId, final Map<String, SerializedValue> arguments)
  throws MerlinServiceException, IOException
  {
    final var request = """
        mutation {
          insert_simulation_one(object: {plan_id: %d, arguments: %s}) {
            id
            revision
          }
        }""".formatted(
            planId.id(),
            simulationArgumentsP.unparse(arguments)
    );
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data");
    final var simulationId = data.get("insert_simulation_one").get("id").intValue();
    return new SimulationId(simulationId);
  }


  private void setSimulationDatasetStatus(SimulationDatasetId id, SimulationStateRecord state)
  throws MerlinServiceException, IOException
  {
    final var request = """
        mutation {
          update_simulation_dataset(where: {id: {_eq: %d}}, _set: {status: %s}) {
            affected_rows
          }
        }
        """.formatted(
        id.id(),
        state.status().label
    );
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data");
    final var affected = data.get("update_simulation_dataset").get("affected_rows").intValue();
    if(affected != 1){
      throw new MerlinServiceException("Unable to modify the status of simulation dataset with id %d".formatted(id.id()));
    }
  }

  private DatasetIds createSimulationDataset(SimulationId simulationId, PlanMetadata planMetadata)
  throws MerlinServiceException, IOException
  {
    final var request = """
        mutation {
          insert_simulation_dataset_one(object: {simulation_id: %d, simulation_start_time:"%s", simulation_end_time:"%s", arguments:{}, status: %s}) {
            id
            dataset_id
          }
        }
        """.formatted(
            simulationId.id(),
            planMetadata.horizon().getStartInstant(),
            planMetadata.horizon().getEndInstant(),
            SimulationStateRecord.Status.INCOMPLETE.label
    );
    final ObjectNode response;
    response = postRequest(request).get();
    final var data = response.get("data");
    final var datasetId = data.get("insert_simulation_dataset_one").get("dataset_id").intValue();
    final var simulationDatasetId = data.get("insert_simulation_dataset_one").get("id").intValue();
    return new DatasetIds(new DatasetId(datasetId), new SimulationDatasetId(simulationDatasetId));
  }

  private static <T> Duration sumDurations(final List<ProfileSegment<Optional<T>>> segments) {
    return segments.stream().reduce(
        Duration.ZERO,
        (acc, pair) -> acc.plus(pair.extent()),
        Duration::plus
    );
  }
  private HashMap<String, ProfileRecord> postResourceProfiles(
      DatasetId datasetId,
      final Map<String,ResourceProfile<Optional<RealDynamics>>> realProfiles,
      final Map<String,ResourceProfile<Optional<SerializedValue>>> discreteProfiles
  ) throws MerlinServiceException, IOException
  {
    final var req = """
        mutation($profiles: [profile_insert_input!]!) {
          insert_profile(objects: $profiles){
            returning {
              id
              name
            }
          }
        }""";
    final var allProfiles = JsonNodeFactory.instance.arrayNode();
    final var resourceNames = new ArrayList<String>();
    final var resourceTypes = new ArrayList<Pair<String, ValueSchema>>();
    final var durations = new ArrayList<Duration>();
    for (final var entry : realProfiles.entrySet()) {
      final var resource = entry.getKey();
      final var schema = entry.getValue().schema();
      final var realResourceType = Pair.of("real", schema);
      final var segments = entry.getValue().segments();
      final var duration = sumDurations(segments);
      resourceNames.add(resource);
      resourceTypes.add(realResourceType);
      durations.add(duration);
      final var profileNode = JsonNodeFactory.instance.objectNode();
      profileNode.put("dataset_id", datasetId.id());
      profileNode.put("duration", graphQLIntervalFromDuration(duration).toString());
      profileNode.put("name", resource);
      profileNode.set("type", realProfileTypeP.unparse(realResourceType));
      allProfiles.add(profileNode);
    }
    for (final var entry : discreteProfiles.entrySet()) {
      final var resource = entry.getKey();
      final var schema = entry.getValue().schema();
      final var resourceType = Pair.of("discrete", schema);
      final var segments = entry.getValue().segments();
      final var duration = sumDurations(segments);
      resourceNames.add(resource);
      resourceTypes.add(resourceType);
      durations.add(duration);
      final var profileNode = JsonNodeFactory.instance.objectNode();
      profileNode.put("dataset_id", datasetId.id());
      profileNode.put("duration", graphQLIntervalFromDuration(duration).toString());
      profileNode.put("name", resource);
      profileNode.set("type", discreteProfileTypeP.unparse(resourceType));
      allProfiles.add(profileNode);
    }
    final var arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("profiles", allProfiles);
    final ObjectNode response;
    response = postRequest(req, arguments).get();
    final var data = response.get("data").get("insert_profile").get("returning");
    final var profileRecords = new HashMap<String, ProfileRecord>(resourceNames.size());
    for (int i = 0; i < resourceNames.size(); i++) {
      final var dataReturned = data.get(i);
      final var resource = resourceNames.get(i);
      final var type = resourceTypes.get(i);
      final var duration = durations.get(i);
      final var id = dataReturned.get("id").intValue();
      final var nameResourceReturned = dataReturned.get("name").textValue();
      if(!nameResourceReturned.equals(resource)){
        throw new MerlinServiceException("Resource do not match");
      }
      profileRecords.put(resource, new ProfileRecord(
          id,
          datasetId.id(),
          resource,
          type,
          duration
      ));
    }
    return profileRecords;
  }

  private void postProfileSegments(
      final DatasetId datasetId,
      final Map<String, ProfileRecord> records,
      final ProfileSet profileSet
  ) throws MerlinServiceException, IOException
  {
    final var realProfiles = profileSet.realProfiles();
    final var discreteProfiles = profileSet.discreteProfiles();
    for (final var entry : records.entrySet()) {
      final ProfileRecord record =  entry.getValue();
      final var resource =  entry.getKey();
      switch (record.type().getLeft()) {
        case "real" -> postRealProfileSegments(
            datasetId,
            record,
            realProfiles.get(resource).segments());
        case "discrete" -> postDiscreteProfileSegments(
            datasetId,
            record,
            discreteProfiles.get(resource).segments());
        default -> throw new Error("Unrecognized profile type " + record.type().getLeft());
      }
    }
  }

  private <Dynamics> void postProfileSegment(
      final DatasetId datasetId,
      final ProfileRecord profileRecord,
      final List<ProfileSegment<Optional<Dynamics>>> segments,
      final JsonParser<Dynamics> dynamicsP
  ) throws MerlinServiceException, IOException
  {
    final var req = """
        mutation($profileSegments:[profile_segment_insert_input!]!) {
          insert_profile_segment(objects: $profileSegments) {
            affected_rows
          }
        }
        """;
    final var profiles = JsonNodeFactory.instance.arrayNode();
    var accumulatedOffset = Duration.ZERO;
    for (final var pair : segments) {
      final var duration = pair.extent();
      final var dynamics = pair.dynamics();

      final JsonNode serializedDynamics;
      final boolean stringIsGap;
      if (dynamics.isPresent()) {
        serializedDynamics = dynamicsP.unparse(dynamics.get());
        stringIsGap = false;
      } else {
        serializedDynamics = NullNode.getInstance();
        stringIsGap = true;
      }
      final var segmentNode = JsonNodeFactory.instance.objectNode();
      segmentNode.put("dataset_id", datasetId.id());
      segmentNode.put("profile_id", profileRecord.id());
      segmentNode.put("start_offset", graphQLIntervalFromDuration(accumulatedOffset).toString());
      segmentNode.put("is_gap", stringIsGap);
      segmentNode.set("dynamics", serializedDynamics);
      profiles.add(segmentNode);
      accumulatedOffset = Duration.add(accumulatedOffset, duration);
    }

    final var arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("profileSegments", profiles);

    final ObjectNode response;
    try {
      response = postRequest(req, arguments).get();
    } catch (MerlinServiceException e) {
      throw new MerlinServiceException(e.toString());
    }
    final var affected_rows = response.get("data").get("insert_profile_segment").get("affected_rows").intValue();
    if(affected_rows!=segments.size()) {
      throw new MerlinServiceException("not the same size");
    }
  }

  private void postRealProfileSegments(final DatasetId datasetId,
                                              final ProfileRecord profileRecord,
                                              final List<ProfileSegment<Optional<RealDynamics>>> segments)
  throws MerlinServiceException, IOException
  {
    postProfileSegment(datasetId, profileRecord, segments, realDynamicsP);
  }

  private void postDiscreteProfileSegments(final DatasetId datasetId,
                                                  final ProfileRecord profileRecord,
                                                  final List<ProfileSegment<Optional<SerializedValue>>> segments)
  throws MerlinServiceException, IOException
  {
    postProfileSegment(datasetId, profileRecord, segments, serializedValueP);
  }

  private void insertSimulationTopics(
      DatasetId datasetId,
      final List<Triple<Integer, String, ValueSchema>> topics) throws MerlinServiceException, IOException
  {
    final var req = """
        mutation($topics:[topic_insert_input!]!) {
          insert_topic(objects: $topics){
            affected_rows
          }
        }
        """;
    final var jsonTopics = JsonNodeFactory.instance.arrayNode();
    for (final var topic : topics) {
      final var topicNode = JsonNodeFactory.instance.objectNode();
      topicNode.put("dataset_id", datasetId.id());
      topicNode.put("topic_index", topic.getLeft());
      topicNode.put("name", topic.getMiddle());
      topicNode.set("value_schema", valueSchemaP.unparse(topic.getRight()));
      jsonTopics.add(topicNode);
    }
    final var arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("topics", jsonTopics);
    postRequest(req, arguments);
  }

  private void insertSimulationEvents(
      DatasetId datasetId,
      Map<Duration, List<EventGraph<EventRecord>>> eventPoints) throws MerlinServiceException, IOException
  {
    final var req = """
            mutation($events:[event_insert_input!]!){
                  insert_event(objects: $events) {
                    affected_rows
                  }
            }
        """;
    final var events = JsonNodeFactory.instance.arrayNode();
    for (final var eventPoint : eventPoints.entrySet()) {
      final var time = eventPoint.getKey();
      final var transactions = eventPoint.getValue();
      for (int transactionIndex = 0; transactionIndex < transactions.size(); transactionIndex++) {
        final var eventGraph = transactions.get(transactionIndex);
        final var flattenedEventGraph = EventGraphFlattener.flatten(eventGraph);
        events.addAll(batchInsertEventGraph(datasetId.id(), time, transactionIndex, flattenedEventGraph));
      }
    }
    final var arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("events", events);
    postRequest(req, arguments);
  }

  private ArrayNode batchInsertEventGraph(
      final long datasetId,
      final Duration duration,
      final int transactionIndex,
      final List<Pair<String, EventRecord>> flattenedEventGraph
  ) {
    final var events = JsonNodeFactory.instance.arrayNode();
    for (final Pair<String, EventRecord> entry : flattenedEventGraph) {
      final var causalTime = entry.getLeft();
      final EventRecord event = entry.getRight();
      final var eventNode = JsonNodeFactory.instance.objectNode();
      eventNode.put("dataset_id", datasetId);
      eventNode.put("real_time", graphQLIntervalFromDuration(duration).toString());
      eventNode.put("transaction_index", transactionIndex);
      eventNode.put("causal_time", causalTime);
      eventNode.put("topic_index", event.topicId());
      eventNode.set("value", JsonEncoding.encode(event.value()));
      eventNode.put("span_id", event.spanId().get());
      events.add(eventNode);
    }
    return events;
  }

  private void postActivities(
      final DatasetId datasetId,
      final Map<ActivityInstanceId, ActivityInstance> simulatedActivities,
      final Map<ActivityInstanceId, UnfinishedActivity> unfinishedActivities,
      final Instant simulationStart,
      final Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap
  ) throws MerlinServiceException, IOException
  {
      final var simulatedActivityRecords = simulatedActivities.entrySet().stream()
                                                              .collect(Collectors.toMap(
                                                                  Map.Entry::getKey,
                                                                  e -> simulatedActivityToRecord(e.getValue())));
      final var allActivityRecords = unfinishedActivities.entrySet().stream()
                                                         .collect(Collectors.toMap(
                                                             Map.Entry::getKey,
                                                             e -> unfinishedActivityToRecord(e.getValue())));
      allActivityRecords.putAll(simulatedActivityRecords);
      postSpans(
          datasetId,
          allActivityRecords,
          simulationStart,
          uploadIdMap
      );
      updateSimulatedActivityParentsAction(
          datasetId,
          simulatedActivityRecords);
  }

  public void updateSimulatedActivityParentsAction(
    final DatasetId datasetId,
    final Map<ActivityInstanceId, SpanRecord> simulatedActivities
) throws MerlinServiceException, IOException
  {
  final var req = """
      mutation($updates:[span_updates!]!) {
        update_span_many(updates: $updates) {
          affected_rows
        }
      }
      """;
  final var updates = JsonNodeFactory.instance.arrayNode();
  int updateCounter = 0;
  for (final var entry : simulatedActivities.entrySet()) {
    final var activity =  entry.getValue();
    final var id =  entry.getKey();
    if (activity.parentId().isEmpty()) continue;
    final var datasetIdEq = JsonNodeFactory.instance.objectNode();
    datasetIdEq.put("_eq", datasetId.id());
    final var spanIdEq = JsonNodeFactory.instance.objectNode();
    spanIdEq.put("_eq", id.id());
    final var whereNode = JsonNodeFactory.instance.objectNode();
    whereNode.set("dataset_id", datasetIdEq);
    whereNode.set("span_id", spanIdEq);
    final var setNode = JsonNodeFactory.instance.objectNode();
    setNode.put("parent_id", activity.parentId().get());
    final var updateNode = JsonNodeFactory.instance.objectNode();
    updateNode.set("where", whereNode);
    updateNode.set("_set", setNode);
    updates.add(updateNode);
    updateCounter++;
  }
  final var arguments = JsonNodeFactory.instance.objectNode();
  arguments.set("updates", updates);

  final ObjectNode response;
  response = postRequest(req, arguments).get();
    final var jsonValue = response.get("data").get("update_span_many");
    var affected_rows = 0;
    if (jsonValue.isArray()) {
      for (final var jsonObject : jsonValue) {
        affected_rows += jsonObject.get("affected_rows").intValue();
      }
    } else {
      affected_rows = jsonValue.get("affected_rows").intValue();
    }
    if(affected_rows != updateCounter) {
      throw new MerlinServiceException("not the same size");
    }
}

  private static SpanRecord simulatedActivityToRecord(final ActivityInstance activity) {
    return new SpanRecord(
        activity.type(),
        activity.start(),
        Optional.of(activity.duration()),
        Optional.ofNullable(activity.parentId()).map(ActivityInstanceId::id),
        activity.childIds().stream().map(ActivityInstanceId::id).collect(Collectors.toList()),
        new ActivityAttributesRecord(
            activity.directiveId().map(ActivityDirectiveId::id),
            activity.arguments(),
            Optional.of(activity.computedAttributes())));
  }

  private static SpanRecord unfinishedActivityToRecord(final UnfinishedActivity activity) {
    return new SpanRecord(
        activity.type(),
        activity.start(),
        Optional.empty(),
        Optional.ofNullable(activity.parentId()).map(ActivityInstanceId::id),
        activity.childIds().stream().map(ActivityInstanceId::id).collect(Collectors.toList()),
        new ActivityAttributesRecord(
            activity.directiveId().map(ActivityDirectiveId::id),
            activity.arguments(),
            Optional.empty()));
  }

  public void postSpans(final DatasetId datasetId,
                                       final Map<ActivityInstanceId, SpanRecord> spans,
                                       final Instant simulationStart,
                                       final Map<ActivityDirectiveId, ActivityDirectiveId> uploadIdMap
  ) throws MerlinServiceException, IOException
  {
    final var req = """
                        mutation($spans:[span_insert_input!]!) {
                        insert_span(objects: $spans) {
                          returning {
                            span_id
                          }
                         }
                        }
                        """;
    final var spansJson = JsonNodeFactory.instance.arrayNode();
    final var ids = spans.keySet().stream().toList();
    for (final var id : ids) {
      final var act = spans.get(id);

      final var startTime = graphQLIntervalFromDuration(simulationStart, act.start);
      final var spanBuilder = JsonNodeFactory.instance.objectNode();
      spanBuilder.put("span_id", id.id());
      spanBuilder.put("dataset_id", datasetId.id());
      spanBuilder.put("start_offset", startTime.toString());
      spanBuilder.put("type", act.type());
      spanBuilder.set("attributes", buildAttributes(
          act.attributes().directiveId().map($ -> uploadIdMap.get(new ActivityDirectiveId($)).id()),
          act.attributes().arguments(),
          act.attributes().computedAttributes()
      ));
      if (act.duration.isPresent()){
        spanBuilder.put("duration", graphQLIntervalFromDuration(act.duration().get()).toString());
      }

      spansJson.add(spanBuilder);
    }
    final var arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("spans", spansJson);
    postRequest(req, arguments).get();
  }

  private JsonNode buildAttributes(final Optional<Long> directiveId, final Map<String, SerializedValue> arguments, final Optional<SerializedValue> returnValue) {
    return activityAttributesP.unparse(new ActivityAttributesRecord(directiveId, arguments, returnValue));
  }

  /**
   * serialize the given string in a manner that can be used as a graphql argument value
   * @param s the string to serialize
   * @return a serialization of the object suitable for use as a graphql value
   */
  public String serializeForGql(final String s) {
    //TODO: can probably leverage some serializers from aerie
    //TODO: (defensive) should escape contents of bare strings, eg internal quotes
    //NB: Time::toString will format correctly as HH:MM:SS.sss, just need to quote it here
    return "\"" + s + "\"";
  }
}
