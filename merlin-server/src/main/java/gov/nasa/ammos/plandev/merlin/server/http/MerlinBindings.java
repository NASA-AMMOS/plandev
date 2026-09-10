package gov.nasa.ammos.plandev.merlin.server.http;

import gov.nasa.ammos.plandev.constraints.InputMismatchException;
import gov.nasa.ammos.plandev.json.FormattedError;
import gov.nasa.ammos.plandev.merlin.driver.MissionModelLoader.MissionModelLoadException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.MerlinFormattedError;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchConstraintException;
import gov.nasa.ammos.plandev.merlin.server.models.ProcedureLoader;
import gov.nasa.ammos.plandev.merlin.server.remotes.postgres.DatabaseException;
import gov.nasa.ammos.plandev.permissions.exceptions.PermissionsException;
import gov.nasa.ammos.plandev.types.SerializedActivity;
import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.ammos.plandev.merlin.server.services.ConstraintAction;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.services.GenerateConstraintsLibAction;
import gov.nasa.ammos.plandev.merlin.server.services.GetSimulationResultsAction;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService;
import gov.nasa.ammos.plandev.merlin.server.services.PlanService;
import gov.nasa.ammos.plandev.merlin.server.services.ExternalModelException;
import gov.nasa.ammos.plandev.merlin.server.services.ExternalResultsGate;
import gov.nasa.ammos.plandev.merlin.server.remotes.ExternalSimulationResultsRepository;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityType;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import gov.nasa.ammos.plandev.permissions.HasuraAction;
import gov.nasa.ammos.plandev.permissions.PermissionsService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.stream.JsonParsingException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.constraintArgumentsP;
import static gov.nasa.ammos.plandev.merlin.server.http.MerlinParsers.parseJson;

import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraActivityActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraActivityBulkActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraConstraintsCodeAction;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraConstraintsViolationsActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraSimulateActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraUploadExternalDatasetActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraRegisterModelTypesActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraIngestExternalSimulationResultsActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraMissionModelActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraMissionModelArgumentsActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraMissionModelEventTriggerP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraPlanActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraExtendExternalDatasetActionP;
import static gov.nasa.ammos.plandev.merlin.server.http.HasuraParsers.hasuraNewConstraintRevisionEventTriggerP;
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
  private final ExternalSimulationResultsRepository externalSimulationResultsRepository;
  private static final Logger logger = LoggerFactory.getLogger(MerlinBindings.class);

  public MerlinBindings(
      final MissionModelService missionModelService,
      final PlanService planService,
      final GetSimulationResultsAction simulationAction,
      final GenerateConstraintsLibAction generateConstraintsLibAction,
      final ConstraintAction constraintAction,
      final PermissionsService permissionsService,
      final ExternalSimulationResultsRepository externalSimulationResultsRepository
  ) {
    this.missionModelService = missionModelService;
    this.planService = planService;
    this.simulationAction = simulationAction;
    this.generateConstraintsLibAction = generateConstraintsLibAction;
    this.constraintAction = constraintAction;
    this.permissionsService = permissionsService;
    this.externalSimulationResultsRepository = externalSimulationResultsRepository;
  }

  /**
   * Store the activity, resource and configuration types of a model that has no JAR.
   *
   * <p>The inverse of the {@code refresh*} endpoints: those derive metadata from a model PlanDev holds,
   * this accepts metadata declared for one it does not.
   */
  private void registerModelTypes(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraRegisterModelTypesActionP).input();

      final var activityTypes = input.activityTypes().stream()
          .collect(Collectors.toMap(ActivityType::name, $ -> $));
      final var resourceTypes = input.resourceTypes().stream()
          .collect(Collectors.toMap(
              gov.nasa.ammos.plandev.merlin.server.models.HasuraAction.ModelResourceType::name,
              gov.nasa.ammos.plandev.merlin.server.models.HasuraAction.ModelResourceType::schema));

      this.missionModelService.registerModelTypes(
          input.missionModelId(), activityTypes, resourceTypes, input.parameters());

      ctx.status(200).result(Json.createObjectBuilder()
          .add("activityTypeCount", activityTypes.size())
          .add("resourceTypeCount", resourceTypes.size())
          .add("parameterCount", input.parameters().size())
          .build().toString());
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final ExternalModelException ex) {
      // 422, not 500. The gate's message IS the product here -- it names the declaration that will not
      // work and why -- and Javalin would otherwise turn this into a bare 500 with a stack trace.
      ctx.status(422).result(serializeExternalModelException(ex).toString());
    }
  }

  /**
   * Build the closed world a pushed result set is checked against: the plan's model types plus the
   * directives the plan actually holds.
   *
   * <p>Unlike the streaming pull path, this REFUSES rather than degrading when the world cannot be
   * built. Nothing downstream re-checks an ingested result -- profiles and spans land in Postgres
   * verbatim -- so ingesting unchecked is the one outcome that cannot be undone. The exception is an
   * operator who has explicitly turned the gate off, whose instruction this should not override.
   */
  private ExternalResultsGate gateForPlan(final PlanId planId, final Duration duration) {
    try {
      final var plan = this.planService.getPlanForSimulation(planId);
      return ExternalResultsGate.of(
          "plan " + planId + " (mission model " + plan.missionModelId() + ")",
          this.missionModelService.getActivityTypes(plan.missionModelId()),
          this.missionModelService.getResourceSchemas(plan.missionModelId()),
          plan.activityDirectives().keySet().stream().map(ActivityDirectiveId::id).collect(Collectors.toSet()),
          duration.in(Duration.MICROSECONDS));
    } catch (final Exception ex) {
      if (ExternalResultsGate.Mode.fromEnv() == ExternalResultsGate.Mode.OFF) {
        logger.warn("Could not build an ingest gate for plan {}, and the gate is disabled; "
                    + "ingesting unchecked ({})", planId, ex.toString());
        return ExternalResultsGate.disabled();
      }
      throw new ExternalModelException(
          ExternalModelException.Kind.INGEST_GATE,
          ("Could not determine what mission model %s declares, so these results cannot be checked "
           + "before they are stored: %s").formatted(planId, ex));
    }
  }

  private void ingestExternalSimulationResults(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraIngestExternalSimulationResultsActionP);
      final var input = body.input();
      final var results = input.results();
      final var requestedBy = body.session().hasuraUserId();

      // The gate is the whole validation story for these results: nothing downstream re-checks them.
      // finish() must run BEFORE the insert, which is why it is here rather than in the repository.
      final var gate = gateForPlan(input.planId(), results.duration());
      gate.checkIngest(results.profiles(), results.spans());
      gate.finish();

      final var simulationDatasetId = this.externalSimulationResultsRepository.insertExternalSimulationResults(
          input.planId(), input.simulationId(),
          results.startTime(), results.duration(), results.profiles(), results.spans(), requestedBy);

      ctx.status(201).result(Json.createObjectBuilder()
          .add("simulationDatasetId", simulationDatasetId)
          .build().toString());
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final ExternalModelException ex) {
      // 422: the request was well-formed and understood, and refused on its content. The findings are
      // the actionable part, so they travel in the body rather than in a log line.
      ctx.status(422).result(serializeExternalModelException(ex).toString());
    }
  }

  /**
   * Hasura's own action-error shape: {@code message} plus {@code extensions}.
   *
   * <p>The nesting is not cosmetic. Hasura surfaces a non-2xx action response as a GraphQL error and
   * merges {@code extensions} into it, but labels anything else {@code code: "unexpected"} -- so a
   * top-level {@code kind} reaches the client as an error that reads like a merlin crash rather than
   * like a refusal. Put the kind in {@code extensions.code} and the client gets both the sentence and
   * something to branch on, without parsing prose.
   */
  private static javax.json.JsonObject serializeExternalModelException(final ExternalModelException ex) {
    return Json.createObjectBuilder()
        .add("message", ex.getMessage() == null ? ex.toString() : ex.getMessage())
        .add("extensions", Json.createObjectBuilder().add("code", ex.kind.name()))
        .build();
  }

  /**
   * The one route that legitimately receives a large body, and is therefore exempt from
   * {@link #ORDINARY_MAX_REQUEST_BYTES}.
   *
   * <p>It carries a whole artifact rather than a handful of fields: a complete simulation result set.
   * A day of one recorded model is 5.6 MB and a week 38.6 MB, so the ordinary limit is not a tight fit
   * for it -- it is the wrong order of magnitude.
   */
  private static final java.util.Set<String> LARGE_BODY_PATHS =
      java.util.Set.of("/ingestExternalSimulationResults");

  /**
   * What every OTHER route may accept. This is Javalin's own former default, kept deliberately.
   *
   * <p>Javalin's size limit is global, so admitting a large body anywhere admits one everywhere --
   * which would put a 256 MB allocation behind every endpoint merlin serves, most of which take a plan
   * id and a couple of strings. Raising the engine's ceiling and re-imposing the small limit here for
   * everything not on the list above keeps the exemption where it is earned.
   */
  private static final long ORDINARY_MAX_REQUEST_BYTES = 1024L * 1024L;

  /**
   * Reject an oversized body before a handler allocates it.
   *
   * <p>Thrown rather than written: a {@code before} handler that merely sets a status does not stop the
   * endpoint that follows it, so the request would be answered twice -- once with the refusal and once
   * by a handler reading the body this was supposed to prevent.
   */
  private static void enforceRequestSize(final Context ctx) {
    if (LARGE_BODY_PATHS.contains(ctx.path())) return;
    if (ctx.contentLength() <= ORDINARY_MAX_REQUEST_BYTES) return;
    throw new io.javalin.http.ContentTooLargeResponse(
        "request body is %d bytes; %s accepts at most %d"
            .formatted(ctx.contentLength(), ctx.path(), ORDINARY_MAX_REQUEST_BYTES));
  }

  @Override
  public void apply(final Javalin javalin) {
    // Since all of these endpoints are Hasura Actions, toggle Formatted Error writing to Hasura style
    FormattedError.FormattedErrorSerializer.USE_HASURA_FORMATTING = true;

    javalin.routes(() -> {
      before(ctx -> ctx.contentType("application/json"));
      before(MerlinBindings::enforceRequestSize);

      path("resourceTypes", () -> post(this::getResourceTypes));
      path("getSimulationResults", () -> post(this::getSimulationResults));
      path("resourceSamples", () -> post(this::getResourceSamples));
      path("constraintViolations", () -> post(this::getConstraintViolations));
      path("refreshModelParameters", () -> post(this::postRefreshModelParameters));
      path("refreshActivityTypes", () -> post(this::postRefreshActivityTypes));
      path("refreshResourceTypes", () -> post(this::postRefreshResourceTypes));
      path("registerModelTypes", () -> post(this::registerModelTypes));
      path("ingestExternalSimulationResults", () -> post(this::ingestExternalSimulationResults));
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
      path("getConstraintProcedureEffectiveArgumentsBulk", () -> post(this::getConstraintProcedureEffectiveArgumentsBulk));
      path("health", () -> get(ctx -> ctx.status(200)));
    });

    // Default exception handlers for common endpoint exceptions
    javalin.exception(JsonException.class,
                      (ex, ctx) -> ctx.status(400)
                                      .json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex)));
    javalin.exception(NoSuchPlanException.class,
                      (ex, ctx) -> ctx.status(404).json(new MerlinFormattedError(ex)));
    javalin.exception(IOException.class, (ex, ctx) -> {
      final var fe = new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex);
      logger.warn("IO Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(
        SQLException.class, (ex, ctx) -> {
          final var fe = new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex);
          logger.warn("SQL Exception: {}", fe);
          ctx.status(500).json(fe);
        });
    javalin.exception(
        UnauthorizedResponse.class, (ex, ctx) -> {
          final var message = ex.getMessage() != null ? ex.getMessage() : "Unauthorized";
          logger.warn("401 Unauthorized: {}", message);
          ctx.status(401).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
        });
    javalin.exception(NumberFormatException.class, (ex, ctx) ->
        ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex)));
    javalin.exception(SecurityException.class, (ex, ctx) -> {
      final var fe = new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex);
      logger.warn("Security Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(DatabaseException.class, (ex, ctx) -> {
      final var fe = new MerlinFormattedError(ex);
      logger.warn("Database Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(MissionModelLoadException.class, (ex, ctx) ->
        ctx.status(500).json(new MerlinFormattedError(ex)));
    javalin.exception(
        HttpResponseException.class, (ex, ctx) ->
            ctx.status(ex.getStatus()).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, "HTTP_RESPONSE_EXCEPTION", ex)));
    javalin.exception(Exception.class, (ex, ctx) -> {
      // Catch-all for unexpected issues
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unknown error.";
      final var fe = new FormattedError(FormattedError.AerieService.MERLIN_SERVER, "UNKNOWN_ERROR", message, ex);
      logger.error("Unexpected error processing request: {}", fe);
      ctx.status(500).json(fe);
    });
  }

  private void postRefreshModelParameters(final Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelEventTriggerP).missionModelId();
      this.missionModelService.refreshModelParameters(missionModelId);
      ctx.status(200);
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    }
  }

  private void postRefreshActivityTypes(final Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelEventTriggerP).missionModelId();
      this.missionModelService.refreshActivityTypes(missionModelId);
      ctx.status(200);
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    }
  }

  private void postRefreshResourceTypes(Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelEventTriggerP).missionModelId();
      this.missionModelService.refreshResourceTypes(missionModelId);
      ctx.status(200);
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    }
  }

  @Deprecated
  private void getResourceTypes(final Context ctx) {
    try {
      final var missionModelId = parseJson(ctx.body(), hasuraMissionModelActionP).input().missionModelId();

      final var schemaMap = this.missionModelService.getResourceSchemas(missionModelId);

      ctx.result(ResponseSerializers.serializeValueSchemas(schemaMap).toString());
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (NoSuchConstraintException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (ProcedureLoader.ProcedureLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    }
  }

  private void getSimulationResults(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraSimulateActionP);
      final var planId = body.input().planId();
      final var force = body.input().force().orElse(false);

      this.checkPermissions(HasuraAction.simulate, body.session(), planId);

      final var response = this.simulationAction.run(planId, force, body.session());
      ctx.result(ResponseSerializers.serializeSimulationResultsResponse(response).toString());
    } catch (PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("Permissions Service Exception: {}", pe.formattedError());
      }
      ctx.status(pe.httpStatusCode()).json(pe.formattedError());
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch(final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    }
  }

  private void getResourceSamples(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraPlanActionP);
      final var planId = body.input().planId();

      this.checkPermissions(HasuraAction.resource_samples, body.session(), planId);

      final var resourceSamples = this.simulationAction.getResourceSamples(planId);
      ctx.json(ResponseSerializers.serializeResourceSamples(resourceSamples).toString());
    } catch (PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("Permissions Service Exception: {}", pe.formattedError());
      }
      ctx.status(pe.httpStatusCode()).json(pe.formattedError());
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    }
  }
  private void getConstraintViolations(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraConstraintsViolationsActionP);
      final var input = body.input();
      final var planId = input.planId();

      this.checkPermissions(HasuraAction.check_constraints, body.session(), planId);

      final var simulationDatasetId = input.simulationDatasetId();
      final var force = input.force().orElse(false);

      final var constraintViolations = this.constraintAction.getViolations(planId, simulationDatasetId, force, body.session());

      ctx.result(ResponseSerializers.serializeConstraintResults(constraintViolations.getLeft(), constraintViolations.getRight()).toString());
    } catch (PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("Permissions Service Exception: {}", pe.formattedError());
      }
      ctx.status(pe.httpStatusCode()).json(pe.formattedError());
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex).toString());
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final InputMismatchException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (SimulationDatasetMismatchException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
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
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final InstantiationException ex) {
      ctx.status(400)
         .result(ResponseSerializers.serializeFailures(List.of(ex.getMessage())).toString());
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelService.NoSuchMissionModelException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final MissionModelLoadException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    }
  }

  private void getConstraintProcedureEffectiveArgumentsBulk(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), constraintArgumentsP());
      final var responses = this.constraintAction.getConstraintProcedureEffectiveArgumentsBulk(input.input());
      ctx.result(ResponseSerializers.serializeIterable(
          ResponseSerializers::serializeConstraintBulkEffectiveArgumentResponse, responses).toString());
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    }
  }


  private void addExternalDataset(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraUploadExternalDatasetActionP);
      final var input = body.input();

      final var planId = input.planId();
      this.checkPermissions(HasuraAction.insert_ext_dataset, body.session(), planId);

      final var simulationDatasetId = input.simulationDatasetId();
      final var datasetStart = input.datasetStart();
      final var profileSet = input.profileSet();

      final var datasetId = this.planService.addExternalDataset(planId, simulationDatasetId, datasetStart, profileSet);

      ctx.status(201).result(ResponseSerializers.serializeCreatedDatasetId(datasetId).toString());
    } catch (PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("Permissions Service Exception: {}", pe.formattedError());
      }
      ctx.status(pe.httpStatusCode()).json(pe.formattedError());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
      ctx.status(404).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
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
        ctx.status(500).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, "Unhandled variant of Constraints Response: " + response));
        return;
      }
      ctx.result(resultString);
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new MerlinFormattedError(ex));
    } catch (final JsonParsingException ex) {
      ctx.status(400).json(new FormattedError(FormattedError.AerieService.MERLIN_SERVER, ex));
    }
  }

  private void checkPermissions(
      final HasuraAction action,
      final gov.nasa.ammos.plandev.merlin.server.models.HasuraAction.Session session,
      final PlanId planId
  ) throws PermissionsException {
    final var permissionsPlanId = new gov.nasa.ammos.plandev.permissions.gql.PlanId(planId.id());
    permissionsService.check(action, session.hasuraRole(), session.hasuraUserId(), permissionsPlanId);
  }

}
