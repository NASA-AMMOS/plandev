package gov.nasa.jpl.aerie.scheduler.server.http;

import javax.json.Json;
import javax.json.stream.JsonParsingException;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import static gov.nasa.jpl.aerie.scheduler.server.http.ResponseSerializers.*;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraSchedulingDSLTypescriptActionP;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraSchedulingGoalEventTriggerP;
import static gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers.hasuraSpecificationActionP;
import static io.javalin.apibuilder.ApiBuilder.*;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.permissions.Action;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.exceptions.ExceptionSerializers;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchSchedulingSpecificationException;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.permissions.exceptions.Unauthorized;
import gov.nasa.jpl.aerie.permissions.gql.SchedulingSpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.services.GenerateSchedulingLibAction;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleAction;
import gov.nasa.jpl.aerie.scheduler.server.services.SchedulerService;
import gov.nasa.jpl.aerie.scheduler.server.services.SpecificationService;
import io.javalin.Javalin;
import io.javalin.http.Context;
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

  private static final Logger log = LoggerFactory.getLogger(SchedulerBindings.class);

  /**
   * apply all scheduler http bindings to the provided javalin server
   *
   * @param javalin the javalin server object to apply bindings to
   */
  @Override
  public void apply(final Javalin javalin) {
    javalin.routes(() -> {
      before(ctx -> ctx.contentType("application/json"));

      path("schedule", () -> post(this::schedule));
      path("health", () -> get(ctx -> ctx.status(200)));
      path("schedulingDslTypescript", () -> post(this::getSchedulingDslTypescript));
      path("refreshSchedulingProcedureParameterTypes", () -> post(this::refreshSchedulingProcedureParameterTypes));
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
      try {
        permissionsService.check(Action.schedule, session.hasuraRole(), session.hasuraUserId(), permissionsSpecId);
      } catch (final IOException ex) {
        // this IOException is caught here so that it isn't mistaken for an IOException during scheduling
        ctx.status(500).result(ExceptionSerializers.serializeIOException(ex).toString());
      }

      final var response = this.scheduleAction.run(specificationId, session);
      ctx.result(serializeScheduleResultsResponse(response).toString());
    } catch (final IOException e) {
      log.error("low level input/output problem during scheduling", e);
      ctx.status(500).result(serializeException(e).toString());
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(serializeInvalidEntityException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(serializeInvalidJsonException(ex).toString());
    } catch (final NoSuchSpecificationException ex) {
      ctx.status(404).result(serializeException(ex).toString());
    } catch (final NoSuchPlanException ex) {
      ctx.status(404).result(ExceptionSerializers.serializeNoSuchPlanException(ex).toString());
    } catch (final NoSuchSchedulingSpecificationException ex) {
      ctx.status(404).result(ExceptionSerializers.serializeNoSuchSchedulingSpecificationException(ex).toString());
    } catch (final PermissionsServiceException ex) {
      ctx.status(503).result(ExceptionSerializers.serializePermissionsServiceException(ex).toString());
    } catch (final Unauthorized ex) {
      ctx.status(403).result(ExceptionSerializers.serializeUnauthorizedException(ex).toString());
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
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(serializeInvalidEntityException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(serializeInvalidJsonException(ex).toString());
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
    } catch (final InvalidEntityException ex) {
      ctx.status(400).result(serializeInvalidEntityException(ex).toString());
    } catch (final InvalidJsonException ex) {
      ctx.status(400).result(serializeInvalidJsonException(ex).toString());
    }
  }

  /**
   * parses the provided json string into the object type understood by the given parser
   *
   * @param jsonStr the input json string to parse
   * @param parser the parser to use to convert it to an object
   * @param <T> the data type of the returned object
   * @return the object represented by the input json string
   * @throws InvalidEntityException if the parser rejects the input json
   * @throws InvalidJsonException if the json structure itself is malformed
   */
  //TODO: unify these little parser utility methods nearby parser code itself (copied from MerlinBindings)
  //TODO: elevate these exceptions to json utility itself
  private <T> T parseJson(final String jsonStr, final JsonParser<T> parser)
  throws InvalidJsonException, InvalidEntityException
  {
    try {
      final var requestJson = Json.createReader(new StringReader(jsonStr)).readValue();
      final var result = parser.parse(requestJson);
      return result.getSuccessOrThrow(reason -> new InvalidEntityException(List.of(reason)));
    } catch (JsonParsingException e) {
      throw new InvalidJsonException(e);
    }
  }
}
