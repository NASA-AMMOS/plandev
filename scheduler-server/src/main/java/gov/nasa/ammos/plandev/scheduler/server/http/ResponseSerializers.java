package gov.nasa.ammos.plandev.scheduler.server.http;

import javax.json.Json;
import javax.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.ammos.plandev.scheduler.server.services.ScheduleAction;
import gov.nasa.ammos.plandev.scheduler.server.services.ScheduleResults;
import org.apache.commons.lang3.tuple.Pair;

import static gov.nasa.ammos.plandev.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

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
    return switch (response) {
      case ScheduleAction.Response.Pending p ->
          Json.createObjectBuilder()
              .add("status", "pending")
              .add("analysisId", p.analysisId())
              .build();
      case ScheduleAction.Response.Incomplete i ->
          Json.createObjectBuilder()
              .add("status", "incomplete")
              .add("analysisId", i.analysisId())
              .build();
      case ScheduleAction.Response.Failed f ->
          Json.createObjectBuilder()
              .add("status", "failed")
              .add("reason", SchedulerParsers.scheduleFailureP.unparse(f.reason()))
              .add("analysisId", f.analysisId())
              .build();
      case ScheduleAction.Response.Complete c -> {
        final var responseJson = Json
            .createObjectBuilder()
            .add("status", "complete")
            .add("results", serializeScheduleResults(c.results()))
            .add("analysisId", c.analysisId());
        if(c.datasetId().isPresent()){
          responseJson.add("datasetId", c.datasetId().get());
        }
        yield responseJson.build();
      }
    };
  }

  public static JsonValue serializeArgument(final SerializedValue parameter) {
    if (parameter == null) return JsonValue.NULL;
    return serializedValueP.unparse(parameter);
  }

  public static JsonValue serializeBulkEffectiveArgumentResponseList(final List<BulkEffectiveArgumentResponse> responses) {
    return serializeIterable(ResponseSerializers::serializeBulkEffectiveArgumentResponse, responses);
  }

  public static JsonValue serializeBulkEffectiveArgumentResponse(BulkEffectiveArgumentResponse response) {
    return switch (response) {
      case BulkEffectiveArgumentResponse.Success s ->
          Json.createObjectBuilder()
              .add("id", s.goalId().id())
              .add("revision", s.goalId().revision())
              .add("success", JsonValue.TRUE)
              .add("arguments",
                   serializeMap(
                       ResponseSerializers::serializeArgument,
                       s.effectiveArguments()))
              .build();
      case BulkEffectiveArgumentResponse.TypeFailure tf ->
          Json.createObjectBuilder()
              .add("id", tf.goalId().id())
              .add("revision", tf.goalId().revision())
              .add("success", JsonValue.FALSE)
              .add("errors", "Goal is not procedural")
              .build();
      case BulkEffectiveArgumentResponse.InstantiationFailure inf ->
          Json.createObjectBuilder(serializeInstantiationException(inf.ex()).asJsonObject())
              .add("id", inf.goalId().id())
              .add("revision", inf.goalId().revision())
              .build();
      case BulkEffectiveArgumentResponse.NoGoalFailure ngf ->
          Json.createObjectBuilder()
             .add("success", JsonValue.FALSE)
             .add("id", ngf.goalId().id())
             .add("revision", ngf.goalId().revision())
             .add("errors", "There is no goal with this id")
             .build();
      case BulkEffectiveArgumentResponse.ProcedureLoadFailure plf ->
          Json.createObjectBuilder()
              .add("success", JsonValue.FALSE)
              .add("id", plf.goalId().id())
              .add("revision", plf.goalId().revision())
              .add("errors", "Error when loading the procedure jar")
              .build();
    };
  }

  public static JsonValue serializeInstantiationException(final gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException ex) {
    return Json.createObjectBuilder()
               .add("success", JsonValue.FALSE)
               .add("errors", Json.createObjectBuilder()
                                  .add("extraneousArguments", serializeStringList(ex.extraneousArguments.stream().map(a -> a.parameterName()).toList()))
                                  .add("unconstructableArguments", serializeIterable(ResponseSerializers::serializeUnconstructableArgument, ex.unconstructableArguments))
                                  .add("missingArguments", serializeStringList(ex.missingArguments.stream().map(a -> a.parameterName()).toList()))
                                  .build())
               .add("arguments", serializeMap(ResponseSerializers::serializeArgument, ex.validArguments.stream().collect(Collectors.toMap(
                   gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException.ValidArgument::parameterName,
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
