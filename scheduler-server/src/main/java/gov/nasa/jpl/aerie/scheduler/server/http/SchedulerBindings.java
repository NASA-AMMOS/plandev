package gov.nasa.jpl.aerie.scheduler.server.http;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.stream.JsonParsingException;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static gov.nasa.jpl.aerie.scheduler.server.http.ResponseSerializers.*;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraBulkProcedureArgumentsP;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraSchedulingDSLTypescriptActionP;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraSchedulingGoalEventTriggerP;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraSpecificationActionP;
import static io.javalin.apibuilder.ApiBuilder.*;

import gov.nasa.jpl.aerie.json.FormattedError;
import gov.nasa.jpl.aerie.json.FormattedError.AerieService;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.permissions.HasuraAction;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsException;
import gov.nasa.jpl.aerie.permissions.gql.SchedulingSpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.SchedulerFormattedError;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.DatabaseException;
import gov.nasa.jpl.aerie.scheduler.server.services.GenerateSchedulingLibAction;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleAction;
import gov.nasa.jpl.aerie.scheduler.server.services.SchedulerService;
import gov.nasa.jpl.aerie.scheduler.server.services.SpecificationService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * set up mapping between scheduler http endpoints and java method calls
 * @param schedulerService object that will service synchronous scheduling api requests (like goal reordering)
 * @param scheduleAction action that initiates scheduling of a plan and collects results, possibly asynchronously
 * @param generateSchedulingLibAction
 * @param permissionsService service that authorizes action requests
 */
public record SchedulerBindings(
    SpecificationService specificationService,
    SchedulerService schedulerService,
    ScheduleAction scheduleAction,
    GenerateSchedulingLibAction generateSchedulingLibAction,
    PermissionsService permissionsService
) implements Plugin {
  public SchedulerBindings {
    Objects.requireNonNull(specificationService);
    Objects.requireNonNull(schedulerService);
    Objects.requireNonNull(scheduleAction);
    Objects.requireNonNull(generateSchedulingLibAction);
    Objects.requireNonNull(permissionsService);
  }

  private static final Logger logger = LoggerFactory.getLogger(SchedulerBindings.class);

  /**
   * apply all scheduler http bindings to the provided javalin server
   *
   * @param javalin the javalin server object to apply bindings to
   */
  @Override
  public void apply(final Javalin javalin) {
    // Since all of these endpoints are Hasura Actions, toggle Formatted Error writing to Hasura style
    FormattedError.FormattedErrorSerializer.USE_HASURA_FORMATTING = true;

    javalin.routes(() -> {
      before(ctx -> ctx.contentType("application/json"));

      path("schedule", () -> post(this::schedule));
      path("health", () -> get(ctx -> ctx.status(200)));
      path("schedulingDslTypescript", () -> post(this::getSchedulingDslTypescript));
      path("refreshSchedulingProcedureParameterTypes", () -> post(this::refreshSchedulingProcedureParameterTypes));
      path("getSchedulingProcedureEffectiveArgumentsBulk", () -> post(this::getSchedulingProcedureEffectiveArgumentsBulk));
    });

    // Default exception handlers for common endpoint exceptions
    javalin.exception(
        JsonException.class,
        (ex, ctx) -> ctx.status(400)
                        .json(new FormattedError(AerieService.SCHEDULER_SERVER, ex)));
    javalin.exception(IOException.class, (ex, ctx) -> {
      final var fe = new FormattedError(AerieService.SCHEDULER_SERVER, ex);
      logger.warn("IO Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(
        SQLException.class, (ex, ctx) -> {
          final var fe = new FormattedError(AerieService.SCHEDULER_SERVER, ex);
          logger.warn("SQL Exception: {}", fe);
          ctx.status(500).json(fe);
        });
    javalin.exception(
        DatabaseException.class, (ex, ctx) -> {
      final var fe = new SchedulerFormattedError(ex);
      logger.warn("Database Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    javalin.exception(
        UnauthorizedResponse.class, (ex, ctx) -> {
          final var message = ex.getMessage() != null ? ex.getMessage() : "Unauthorized";
          logger.warn("401 Unauthorized: {}", message);
          ctx.status(401).json(new FormattedError(AerieService.SCHEDULER_SERVER, ex));
        });
    javalin.exception(NumberFormatException.class, (ex, ctx) ->
        ctx.status(400).json(new FormattedError(AerieService.SCHEDULER_SERVER, ex)));
    javalin.exception(SecurityException.class, (ex, ctx) -> {
      final var fe = new FormattedError(AerieService.SCHEDULER_SERVER, ex);
      logger.warn("Security Exception: {}", fe);
      ctx.status(500).json(fe);
    });
    //javalin.exception(
      //  MissionModelLoader.MissionModelLoadException.class, (ex, ctx) ->
        //    ctx.status(500).json(new MerlinFormattedError(ex)));
    javalin.exception(
        HttpResponseException.class, (ex, ctx) ->
            ctx.status(ex.getStatus()).json(new FormattedError(AerieService.SCHEDULER_SERVER, "HTTP_RESPONSE_EXCEPTION", ex)));
    javalin.exception(Exception.class, (ex, ctx) -> {
      // Catch-all for unexpected issues
      final var message = ex.getMessage() != null ? ex.getMessage() : "Unknown error.";
      final var fe = new FormattedError(AerieService.SCHEDULER_SERVER, "UNKNOWN_ERROR", message, ex);
      logger.error("Unexpected error processing workspace request {}", fe);
      ctx.status(500).json(fe);
    });
  }

  /**
   * action bound to the /schedule endpoint: runs the scheduler on the provided input plan and goals
   *
   * @param ctx the http context of the request from which to read input or post results
   */
  private void schedule(final Context ctx) {
    try {
      //TODO: is plan enough to locate goal set to use, or need more args in body?
      final var body = parseJson(ctx.body(), hasuraSpecificationActionP);
      final var specificationId = body.input().specificationId();

      final var session = body.session();
      final var permissionsSpecId = new SchedulingSpecificationId(specificationId.id());

      permissionsService.check(HasuraAction.schedule, session.hasuraRole(), session.hasuraUserId(), permissionsSpecId);

      final var response = this.scheduleAction.run(specificationId, session);
      ctx.result(serializeScheduleResultsResponse(response).toString());
    } catch (final PermissionsException pe) {
      if (pe.httpStatusCode() == 500) {
        logger.warn("Permissions Service Exception: {}", pe.formattedError());
      }
      ctx.status(pe.httpStatusCode()).json(pe.formattedError());
    } catch (final IOException e) {
      logger.error("IO Exception: ", e);
      ctx.status(500).json(new FormattedError(AerieService.SCHEDULER_SERVER, e));
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new SchedulerFormattedError(ex));
    } catch (final NoSuchSpecificationException ex) {
      ctx.status(404).json(new SchedulerFormattedError(ex));
    }
  }

  /**
   * action bound to the /schedulingDslTypescript endpoint: generates the typescript code for a given mission model
   *
   * @param ctx the http context of the request from which to read input or post results
   */
  private void getSchedulingDslTypescript(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraSchedulingDSLTypescriptActionP);
      final var missionModelId = body.input().missionModelId();
      final var planId = body.input().planId();
      final var response = this.generateSchedulingLibAction.run(missionModelId, planId);
      final String resultString;
      if (response instanceof GenerateSchedulingLibAction.Response.Success r) {
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
      } else if (response instanceof GenerateSchedulingLibAction.Response.Failure r) {
        resultString = Json
            .createObjectBuilder()
            .add("status", "failure")
            .add("reason", r.reason())
            .build().toString();
      } else {
        throw new Error("Unhandled variant of Response: " + response);
      }
      ctx.result(resultString);
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new SchedulerFormattedError(ex));
    }
  }

  /**
   * action bound to the /refreshSchedulingProcedureParameterTypes endpoint
   *
   * Responsible for loading an uploaded procedure jar, asking for its parameter value schema and saving that to the database
   *
   * @param ctx the http context of the request from which to read input or post results
   */
  private void refreshSchedulingProcedureParameterTypes(final Context ctx) {
    try {
      final var body = parseJson(ctx.body(), hasuraSchedulingGoalEventTriggerP);
      final var goalId = body.goalId();
      final var revision = body.revision();
      this.specificationService.refreshSchedulingProcedureParameterTypes(goalId, revision);
      ctx.status(200);
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new SchedulerFormattedError(ex));
    }
  }

  private void getSchedulingProcedureEffectiveArgumentsBulk(final Context ctx) {
    try {
      final var input = parseJson(ctx.body(), hasuraBulkProcedureArgumentsP());

      final var responses = this.specificationService.getSchedulingProcedureEffectiveArguments(input.input().items());
      ctx.result(ResponseSerializers.serializeBulkEffectiveArgumentResponseList(responses).toString());
    } catch (final InvalidJsonEntityException ex) {
      ctx.status(400).json(new SchedulerFormattedError(ex));
    }
  }

  /**
   * parses the provided json string into the object type understood by the given parser
   *
   * @param jsonStr the input json string to parse
   * @param parser the parser to use to convert it to an object
   * @param <T> the data type of the returned object
   * @return the object represented by the input json string
   * @throws InvalidJsonEntityException if the parser rejects the input json
   */
  //TODO: unify these little parser utility methods nearby parser code itself (copied from MerlinBindings)
  //TODO: elevate these exceptions to json utility itself
  private <T> T parseJson(final String jsonStr, final JsonParser<T> parser)
  throws JsonParsingException, InvalidJsonEntityException
  {
    final var requestJson = Json.createReader(new StringReader(jsonStr)).readValue();
    final var result = parser.parse(requestJson);
    return result.getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)));
  }
}
