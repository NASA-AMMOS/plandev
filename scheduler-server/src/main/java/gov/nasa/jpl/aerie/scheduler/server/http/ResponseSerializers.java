package gov.nasa.jpl.aerie.scheduler.server.http;

import javax.json.Json;
import javax.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleAction;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import org.apache.commons.lang3.tuple.Pair;

/**
 * json serialization methods for data entities used in the scheduler response bodies
 */
public class ResponseSerializers {

  public static <T> JsonValue
  serializeIterable(final Function<T, JsonValue> elementSerializer, final Iterable<T> elements) {
    if (elements == null) return JsonValue.NULL;

    final var builder = Json.createArrayBuilder();
    for (final var element : elements) builder.add(elementSerializer.apply(element));
    return builder.build();
  }

  public static <T> JsonValue serializeMap(final Function<T, JsonValue> fieldSerializer, final Map<String, T> fields) {
    if (fields == null) return JsonValue.NULL;

    final var builder = Json.createObjectBuilder();
    for (final var entry : fields.entrySet()) builder.add(entry.getKey(), fieldSerializer.apply(entry.getValue()));
    return builder.build();
  }

  /**
   * serialize the scheduler run result, including if it is incomplete/failed
   *
   * @param response the result of the scheduling run to serialize
   * @return a json serialization of the scheduling run result
   */
  public static JsonValue serializeScheduleResultsResponse(final ScheduleAction.Response response) {
    if (response instanceof final ScheduleAction.Response.Pending r) {
      return Json
          .createObjectBuilder()
          .add("status", "pending")
          .add("analysisId", r.analysisId())
          .build();
    } else if (response instanceof ScheduleAction.Response.Incomplete r) {
      return Json
          .createObjectBuilder()
          .add("status", "incomplete")
          .add("analysisId", r.analysisId())
          .build();
    } else if (response instanceof ScheduleAction.Response.Failed r) {
      return Json
          .createObjectBuilder()
          .add("status", "failed")
          .add("reason", SchedulerParsers.scheduleFailureP.unparse(r.reason()))
          .add("analysisId", r.analysisId())
          .build();
    } else if (response instanceof ScheduleAction.Response.Complete r) {
      final var responseJson = Json
          .createObjectBuilder()
          .add("status", "complete")
          .add("results", serializeScheduleResults(r.results()))
          .add("analysisId", r.analysisId());
      if(r.datasetId().isPresent()){
        responseJson.add("datasetId", r.datasetId().get());
      }
      return responseJson.build();
    } else {
      throw new UnexpectedSubtypeError(ScheduleAction.Response.class, response);
    }
  }

  /**
   * serialize the provided scheduling result summary to json
   *
   * @param results the scheduling results to serialize
   * @return a json serialization of the given scheduling result
   */
  public static JsonValue serializeScheduleResults(final ScheduleResults results)
  {
    return serializeMap(
        ResponseSerializers::serializeGoalResult,
        results.goalResults()
            .entrySet()
            .stream()
            .collect(
                Collectors.toMap(e -> Long.toString(e.getKey().goalInvocationId().get()), Map.Entry::getValue)));
  }

  private static JsonValue serializeGoalResult(final ScheduleResults.GoalResult goalResult) {
    return Json
        .createObjectBuilder()
        .add("createdActivities", serializeIterable(
            id -> Json.createValue(id.id()),
            goalResult.createdActivities()))
        .add("satisfyingActivities", serializeIterable(
            id -> Json.createValue(id.id()),
            goalResult.satisfyingActivities()))
        .add("createdActivities", goalResult.satisfied())
        .build();
  }

  private static JsonValue serializeUserCodeError(final SchedulingCompilationError.UserCodeError error) {
    return Json.createObjectBuilder()
        .add("message", error.message())
        .add("stack", error.stack())
        .add("location", Json.createObjectBuilder()
            .add("line", error.location().line())
            .add("column", error.location().column()))
        .build();
  }

  public static JsonValue serializeFailedGlobalSchedulingConditions(
      final List<List<SchedulingCompilationError.UserCodeError>> failedGlobalSchedulingConditions)
  {
    return serializeIterable(
        errors -> serializeIterable(ResponseSerializers::serializeUserCodeError, errors),
        failedGlobalSchedulingConditions);
  }

  public static JsonValue serializeFailedGoals(final List<Pair<GoalId, List<SchedulingCompilationError.UserCodeError>>> failedGoals) {
    return serializeIterable(
        goalFailures -> Json.createObjectBuilder()
            .add("goal_id", goalFailures.getKey().id())
            .add("errors", serializeIterable(ResponseSerializers::serializeUserCodeError, goalFailures.getValue()))
            .build(),
        failedGoals);
  }

  public static JsonValue serializeNoSuchSpecificationException(final NoSuchSpecificationException e) {
    return Json.createObjectBuilder().add("specification_id", e.specificationId.id()).build();
  }

  public static JsonValue serializeNoSuchPlanException(final NoSuchPlanException e) {
    return Json.createObjectBuilder().add("specification_id", e.getInvalidPlanId().id()).build();
  }

  /**
   * create report of given exception that can be passed as json payload
   *
   * @param e the exception to generate json report for
   * @return a json serialization of the exception details
   */
  public static JsonValue serializeException(final Exception e) {
    //TODO: stack trace or other details back to ui / client?
    return Json.createObjectBuilder()
               .add("message", e.toString())
               .build();
  }

  public static JsonValue serializeFailureReason(final JsonParseResult.FailureReason failure) {
    return Json.createObjectBuilder()
               .add("breadcrumbs", serializeIterable(ResponseSerializers::serializeParseFailureBreadcrumb, failure.breadcrumbs()))
               .add("message", failure.reason())
               .build();
  }

  public static JsonValue serializeParseFailureBreadcrumb(final gov.nasa.jpl.aerie.json.Breadcrumb breadcrumb) {
    return breadcrumb.visit(new gov.nasa.jpl.aerie.json.Breadcrumb.BreadcrumbVisitor<>() {
      @Override
      public JsonValue onString(final String s) {
        return Json.createValue(s);
      }

      @Override
      public JsonValue onInteger(final Integer i) {
        return Json.createValue(i);
      }
    });
  }

  public static JsonValue serializeInvalidJsonException(final InvalidJsonException ex) {
    return Json.createObjectBuilder()
               .add("kind", "invalid-entity")
               .add("message", "invalid json")
               .build();
  }

  public static JsonValue serializeInvalidEntityException(final InvalidEntityException ex) {
    return Json.createObjectBuilder()
               .add("kind", "invalid-entity")
               .add("failures", serializeIterable(ResponseSerializers::serializeFailureReason, ex.failures))
               .build();
  }

  public static JsonValue serializeValueSchema(final ValueSchema schema) {
    if (schema == null) return JsonValue.NULL;

    return new ValueSchemaJsonParser().unparse(schema);
  }
}
