package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.services.ConstraintAction;
import gov.nasa.jpl.aerie.merlin.server.models.HasuraAction;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.services.GenerateConstraintsLibAction;
import gov.nasa.jpl.aerie.merlin.server.services.GetSimulationResultsAction;
import gov.nasa.jpl.aerie.merlin.server.services.LocalMissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.PlanService;
import gov.nasa.jpl.aerie.permissions.Action;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.exceptions.ExceptionSerializers;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.permissions.exceptions.Unauthorized;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.plugin.Plugin;

import javax.json.Json;
import javax.json.stream.JsonParsingException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers.parseJson;

import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraActivityActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraActivityBulkActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraConstraintsCodeAction;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraConstraintsViolationsActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraSimulateActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraUploadExternalDatasetActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraMissionModelActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraMissionModelArgumentsActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraMissionModelEventTriggerP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraPlanActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraExtendExternalDatasetActionP;
import static gov.nasa.jpl.aerie.merlin.server.http.HasuraParsers.hasuraNewConstraintRevisionEventTriggerP;
import static io.javalin.apibuilder.ApiBuilder.before;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.get;

/**
 * Lift native Java agents into an HTTP-oriented service.
 *
 * The role of a {@code MerlinBindings} object is to faithfully translate between the request/response protocol
 * of HTTP and the call/return/throw protocol of a Java method. Put differently, {@code MerlinBindings} <i>lifts</i>
 * an object with native Java endpoints (methods) into an HTTP service with HTTP-oriented endpoints. This entails
 * translating HTTP request bodies into native Java domain objects, and translating native Java domain objects
 * (including thrown exceptions) into HTTP response bodies.
 *
 * The objects being lifted implement the {@link MissionModelService} and {@link GetSimulationResultsAction} interfaces.
 * Formally, these interfaces are the ones {@code MerlinBindings} class lifts into the domain of HTTP;
 * an object implementing the interface defines the action to take for each HTTP request in an HTTP-independent way.
 */
public final class MerlinBindings implements Plugin {
  private final MissionModelService missionModelService;
  private final PlanService planService;
  private final GetSimulationResultsAction simulationAction;
  private final GenerateConstraintsLibAction generateConstraintsLibAction;
  private final ConstraintAction constraintAction;
  private final PermissionsService permissionsService;

  public MerlinBindings(
      final MissionModelService missionModelService,
      final PlanService planService,
      final GetSimulationResultsAction simulationAction,
      final GenerateConstraintsLibAction generateConstraintsLibAction,
      final ConstraintAction constraintAction,
      final PermissionsService permissionsService
  ) {
    this.missionModelService = missionModelService;
    this.planService = planService;
    this.simulationAction = simulationAction;
    this.generateConstraintsLibAction = generateConstraintsLibAction;
    this.constraintAction = constraintAction;
    this.permissionsService = permissionsService;
  }

  @Override
  public void apply(final Javalin javalin) {
    javalin.routes(() -> {
      before(ctx -> ctx.contentType("application/json"));

      path("resourceTypes", () -> post(this::getResourceTypes));
      path("getSimulationResults", () -> post(this::getSimulationResults));
      path("resourceSamples", () -> post(this::getResourceSamples));
      path("constraintViolations", () -> post(this::getConstraintViolations));
      path("refreshModelParameters", () -> post(this::postRefreshModelParameters));
      path("refreshActivityTypes", () -> post(this::postRefreshActivityTypes));
      path("refreshResourceTypes", () -> post(this::postRefreshResourceTypes));
      path("validateActivityArguments", () -> post(this::validateActivityArguments));
      path("validateModelArguments", () -> post(this::validateModelArguments));
      path("validatePlan", () -> post(this::validatePlan));
      path("getModelEffectiveArguments", () -> post(this::getModelEffectiveArguments));
      path("getActivityEffectiveArguments", () -> post(this::getActivityEffectiveArguments));
      path("getActivityEffectiveArgumentsBulk", () -> post(this::getActivityEffectiveArgumentsBulk));
      path("addExternalDataset", () -> post(this::addExternalDataset));
      path("extendExternalDataset", () -> post(this::extendExternalDataset));
      path("constraintsDslTypescript", () -> post(this::getConstraintsDslTypescript));
      path("refreshConstraintProcedureParameterTypes", () -> post(this::refreshConstrainProcedureParameterTypes));
      path("health", () -> get(ctx -> ctx.status(200)));
    });

    // This exception is expected when the request body entity is not a legal JsonValue.
    javalin.exception(JsonParsingException.class, (ex, ctx) -> ctx
        .status(400)
        .result(ResponseSerializers.serializeJsonParsingException(ex).toString())
        .contentType("application/json"));
  }

  private void postRefreshModelParameters(final Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelEventTriggerP).missionModelId();
      this.missionModelService.refreshModelParameters(missionModelId);
      ctx.status(200);
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void postRefreshActivityTypes(final Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelEventTriggerP).missionModelId();
      this.missionModelService.refreshActivityTypes(missionModelId);
      ctx.status(200);
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void postRefreshResourceTypes(Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelEventTriggerP).missionModelId();
      this.missionModelService.refreshResourceTypes(missionModelId);
      ctx.status(200);
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  @Deprecated
  private void getResourceTypes(final Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelActionP).input().missionModelId();

      final var schemaMap = this.missionModelService.getResourceSchemas(missionModelId);

      ctx.result(ResponseSerializers.serializeValueSchemas(schemaMap).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

    /**
   * action bound to the /refreshSchedulingProcedureParameterTypes endpoint
   *
   * Responsible for loading an uploaded procedure jar, asking for its parameter value schema and saving that to the database
   *
   * @param ctx the http context of the request from which to read input or post results
   */
  private void refreshConstrainProcedureParameterTypes(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraNewConstraintRevisionEventTriggerP);
      final var constraintId = body.constraintId();
      final var revision = body.revision();
      this.constraintAction.refreshConstraintProcedureParameterTypes(constraintId, revision);
      ctx.status(200);
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    }
  }

  private void getSimulationResults(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraSimulateActionP);
      final var planId = body.input().planId();
      final var force = body.input().force().orElse(false);

      this.checkPermissions(Action.simulate, body.session(), planId);

      final var response = this.simulationAction.run(planId, force, body.session());
      ctx.result(ResponseSerializers.serializeSimulationResultsResponse(response).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch(final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException ex) {
      ctx.status(404).result(ExceptionSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final PermissionsServiceException ex) {
      ctx.status(503).result(ExceptionSerializers.serializePermissionsServiceException(ex).toString());
    } catch (final Unauthorized ex) {
      ctx.status(403).result(ExceptionSerializers.serializeUnauthorizedException(ex).toString());
    } catch (final IOException ex) {
      ctx.status(500).result(ExceptionSerializers.serializeIOException(ex).toString());
    }
  }

  private void getResourceSamples(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraPlanActionP);
      final var planId = body.input().planId();

      this.checkPermissions(Action.resource_samples, body.session(), planId);

      final var resourceSamples = this.simulationAction.getResourceSamples(planId);
      ctx.result(ResponseSerializers.serializeResourceSamples(resourceSamples).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException ex) {
      ctx.status(404).result(ExceptionSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final PermissionsServiceException ex) {
      ctx.status(503).result(ExceptionSerializers.serializePermissionsServiceException(ex).toString());
    } catch (final Unauthorized ex) {
      ctx.status(403).result(ExceptionSerializers.serializeUnauthorizedException(ex).toString());
    } catch (final IOException ex) {
      ctx.status(500).result(ExceptionSerializers.serializeIOException(ex).toString());
    }
}
  private void getConstraintViolations(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraConstraintsViolationsActionP);
      final var input = body.input();
      final var planId = input.planId();

      this.checkPermissions(Action.check_constraints, body.session(), planId);

      final var simulationDatasetId = input.simulationDatasetId();
      final var force = input.force().orElse(false);

      final var constraintViolations = this.constraintAction.getViolations(planId, simulationDatasetId, force, body.session());

      ctx.result(ResponseSerializers.serializeConstraintResults(constraintViolations.getLeft(), constraintViolations.getRight()).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException ex) {
      ctx.status(404).result(ExceptionSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final InputMismatchException ex) {
      ctx.status(404).result(ResponseSerializers.serializeInputMismatchException(ex).toString());
    } catch (SimulationDatasetMismatchException ex) {
      ctx.status(404).result(ResponseSerializers.serializeSimulationDatasetMismatchException(ex).toString());
    } catch (final PermissionsServiceException ex) {
      ctx.status(503).result(ExceptionSerializers.serializePermissionsServiceException(ex).toString());
    } catch (final Unauthorized ex) {
      ctx.status(403).result(ExceptionSerializers.serializeUnauthorizedException(ex).toString());
    } catch (final IOException ex) {
      ctx.status(500).result(ExceptionSerializers.serializeIOException(ex).toString());
    }
  }

  private void validateActivityArguments(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraActivityActionP).input();

      final var missionModelId = input.missionModelId();
      final var activityTypeName = input.activityTypeName();
      final var activityArguments = input.arguments();

      final var serializedActivity = new SerializedActivity(activityTypeName, activityArguments);

      final var notices = this.missionModelService.validateActivityArguments(missionModelId, serializedActivity);

      ctx.result(ResponseSerializers.serializeValidationNotices(notices).toString());
    } catch (final InstantiationException ex) {
      ctx.status(400)
         .result(ResponseSerializers.serializeFailures(List.of(ex.getMessage())).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void validateModelArguments(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraMissionModelArgumentsActionP).input();

      final var missionModelId = input.missionModelId();
      final var arguments = input.arguments();
      final var notices = this.missionModelService.validateModelArguments(missionModelId, arguments);

      ctx.result(ResponseSerializers.serializeValidationNotices(notices).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final InstantiationException ex) {
      ctx.status(400)
         .result(ResponseSerializers.serializeFailures(List.of(ex.getMessage())).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void validatePlan(final Context ctx) {
    try {
      final var planId = parseJson(ctx.body(), hasuraPlanActionP).input().planId();

      final var plan = this.planService.getPlanForValidation(planId);
      final var activities = plan.activityDirectives().entrySet().stream().collect(Collectors.toMap(
          Map.Entry::getKey,
          e -> e.getValue().serializedActivity()));
      final var failures = this.missionModelService.validateActivityInstantiations(plan.missionModelId(), activities);

      ctx.result(ResponseSerializers.serializeUnconstructableActivityFailures(failures).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void getModelEffectiveArguments(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraMissionModelArgumentsActionP).input();

      final var missionModelId = input.missionModelId();
      final var arguments = this.missionModelService.getModelEffectiveArguments(missionModelId, input.arguments());

      ctx.result(ResponseSerializers.serializeEffectiveArgumentMap(arguments).toString());
    } catch (final InstantiationException ex) {
      ctx.status(200).result(ResponseSerializers.serializeInstantiationException(ex).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  @Deprecated
  private void getActivityEffectiveArguments(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraActivityActionP).input();

      final var missionModelId = input.missionModelId();
      final var activityTypeName = input.activityTypeName();
      final var activityArguments = input.arguments();

      final var serializedActivity = new SerializedActivity(activityTypeName, activityArguments);

      final var arguments = this.missionModelService.getActivityEffectiveArgumentsBulk(
          missionModelId,
          List.of(serializedActivity));

      ctx.result(ResponseSerializers.serializeBulkEffectiveArgumentResponse(arguments.get(0)).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void getActivityEffectiveArgumentsBulk(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraActivityBulkActionP).input();
      final var missionModelId = input.missionModelId();
      final var activities = input.activities();

      final var response = this.missionModelService.getActivityEffectiveArgumentsBulk(missionModelId, activities);

      ctx.result(ResponseSerializers.serializeBulkEffectiveArgumentResponseList(response).toString());
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchMissionModelException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final LocalMissionModelService.MissionModelLoadException ex) {
      ctx.status(400).result(ResponseSerializers.serializeMissionModelLoadException(ex).toString());
    }
  }

  private void addExternalDataset(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraUploadExternalDatasetActionP);
      final var input = body.input();

      final var planId = input.planId();
      this.checkPermissions(Action.insert_ext_dataset, body.session(), planId);

      final var simulationDatasetId = input.simulationDatasetId();
      final var datasetStart = input.datasetStart();
      final var profileSet = input.profileSet();

      final var datasetId = this.planService.addExternalDataset(planId, simulationDatasetId, datasetStart, profileSet);

      ctx.status(201).result(ResponseSerializers.serializeCreatedDatasetId(datasetId).toString());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException ex) {
      ctx.status(404).result(ExceptionSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final PermissionsServiceException ex) {
      ctx.status(503).result(ExceptionSerializers.serializePermissionsServiceException(ex).toString());
    } catch (final Unauthorized ex) {
      ctx.status(403).result(ExceptionSerializers.serializeUnauthorizedException(ex).toString());
    } catch (final IOException ex) {
      ctx.status(500).result(ExceptionSerializers.serializeIOException(ex).toString());
    }
  }

  private void extendExternalDataset(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraExtendExternalDatasetActionP);
      final var datasetId = body.input().datasetId();

      final var profileSet = body.input().profileSet();
      this.planService.extendExternalDataset(datasetId, profileSet);

      ctx.status(200).result(
          Json
              .createObjectBuilder()
              .add("datasetId", datasetId.id())
              .build().toString());
    } catch (final NoSuchPlanDatasetException ex) {
      ctx.status(404).result(ResponseSerializers.serializeNoSuchPlanDatasetException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    }
  }

  /**
   * action bound to the /constraintsDslTypescript endpoint: generates the typescript code for a given mission model
   *
   * @param ctx the http context of the request from which to read input or post results
   */
  private void getConstraintsDslTypescript(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraConstraintsCodeAction);
      final var missionModelId = body.input().missionModelId();
      final var planId = body.input().planId();

      final var response = this.generateConstraintsLibAction.run(missionModelId, planId);
      final String resultString;
      if (response instanceof GenerateConstraintsLibAction.Response.Success r) {
        var files = Json.createArrayBuilder();
        for (final var entry : r.files().entrySet()) {
          files = files.add(
              Json.createObjectBuilder()
                  .add("filePath", entry.getKey())
                  .add("content", entry.getValue())
                  .build());
        }
        resultString = Json
            .createObjectBuilder()
            .add("status", "success")
            .add("typescriptFiles", files)
            .build().toString();
      } else if (response instanceof GenerateConstraintsLibAction.Response.Failure r) {
        resultString = Json
            .createObjectBuilder()
            .add("status", "failure")
            .add("reason", r.reason())
            .build().toString();
      } else {
        throw new Error("Unhandled variant of Response: " + response);
      }
      ctx.result(resultString);
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidEntityException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(ResponseSerializers.serializeInvalidJsonException(ex).toString());
    }
  }

  private void checkPermissions(
      final Action action,
      final HasuraAction.Session session,
      final PlanId planId
  ) throws gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException, Unauthorized, IOException, PermissionsServiceException
  {
    final var permissionsPlanId = new gov.nasa.jpl.aerie.permissions.gql.PlanId(planId.id());
    permissionsService.check(action, session.hasuraRole(), session.hasuraUserId(), permissionsPlanId);
  }

}
