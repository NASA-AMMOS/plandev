package gov.nasa.jpl.aerie.scheduler.server.http;

import javax.json.Json;
import javax.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSchedulingGoalException;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleAction;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import org.apache.commons.lang3.tuple.Pair;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

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

  public static JsonValue serializeArgument(final SerializedValue parameter) {
    if (parameter == null) return JsonValue.NULL;
    return serializedValueP.unparse(parameter);
  }

  public static JsonValue serializeBulkEffectiveArgumentResponseList(final List<BulkEffectiveArgumentResponse> responses) {
    return serializeIterable(ResponseSerializers::serializeBulkEffectiveArgumentResponse, responses);
  }

  public static JsonValue serializeBulkEffectiveArgumentResponse(BulkEffectiveArgumentResponse response) {
    // TODO use pattern matching in switch statement with JDK 21
    if (response instanceof BulkEffectiveArgumentResponse.Success(
        GoalId goalId, Map<String, SerializedValue> effectiveArguments)) {
      return Json.createObjectBuilder()
                 .add("id", goalId.id())
                 .add("revision", goalId.revision())
                 .add("success", JsonValue.TRUE)
                 .add("arguments",
                      serializeMap(
                          ResponseSerializers::serializeArgument,
                          effectiveArguments))
                 .build();
    } else if (response instanceof BulkEffectiveArgumentResponse.TypeFailure(GoalId goalId)) {
      return Json.createObjectBuilder()
                 .add("id", goalId.id())
                 .add("revision", goalId.revision())
                 .add("success", JsonValue.FALSE)
                 .add("errors", "Goal is not procedural")
                 .build();
    } else if (response instanceof BulkEffectiveArgumentResponse.InstantiationFailure(
        GoalId goalId,
        InstantiationException ex)) {
      return Json.createObjectBuilder(serializeInstantiationException(ex).asJsonObject())
                 .add("id", goalId.id())
                 .add("revision", goalId.revision())
                 .build();
    }
    else if (response instanceof BulkEffectiveArgumentResponse.NoGoalFailure(
        GoalId goalId,
        NoSuchSchedulingGoalException ex)) {
      return Json.createObjectBuilder()
                 .add("success", JsonValue.FALSE)
                 .add("id", goalId.id())
                 .add("revision", goalId.revision())
                 .add("errors", "There is no goal with this id")
                 .build();
    }
    else if (response instanceof BulkEffectiveArgumentResponse.ProcedureLoadFailure(
       GoalId goalId,
       ProcedureLoader.ProcedureLoadException ex)) {
      return Json.createObjectBuilder()
                 .add("success", JsonValue.FALSE)
                 .add("id", goalId.id())
                 .add("revision", goalId.revision())
                 .add("errors", "Error when loading the procedure jar")
                 .build();
    }
    return Json.createObjectBuilder()
               .add("success", JsonValue.FALSE)
               .add("errors", String.format("Internal error: %s", response))
               .build();
  }

  public static JsonValue serializeInstantiationException(final gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException ex) {
    return Json.createObjectBuilder()
               .add("success", JsonValue.FALSE)
               .add("errors", Json.createObjectBuilder()
                                  .add("extraneousArguments", serializeStringList(ex.extraneousArguments.stream().map(a -> a.parameterName()).toList()))
                                  .add("unconstructableArguments", serializeIterable(ResponseSerializers::serializeUnconstructableArgument, ex.unconstructableArguments))
                                  .add("missingArguments", serializeStringList(ex.missingArguments.stream().map(a -> a.parameterName()).toList()))
                                  .build())
               .add("arguments", serializeMap(ResponseSerializers::serializeArgument, ex.validArguments.stream().collect(Collectors.toMap(
                   gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException.ValidArgument::parameterName,
                   InstantiationException.ValidArgument::serializedValue))))
               .build();
  }

  public static JsonValue serializeStringList(final List<String> elements) {
    return serializeIterable(ResponseSerializers::serializeString, elements);
  }

  public static JsonValue serializeString(final String value) {
    if (value == null) return JsonValue.NULL;
    return Json.createValue(value);
  }

  private static JsonValue serializeUnconstructableArgument(
      final InstantiationException.UnconstructableArgument argument)
  {
    return Json.createObjectBuilder()
               .add("name", argument.parameterName())
               .add("failure", argument.failure())
               .build();
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
}
