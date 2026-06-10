package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.MerlinFormattedError;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.ValidationNotice;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintsCompilationError;
import gov.nasa.jpl.aerie.merlin.server.services.BulkConstraintEffectiveArgumentResponse;
import gov.nasa.jpl.aerie.merlin.server.services.GetSimulationResultsAction;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.BulkEffectiveArgumentResponse;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.BulkArgumentValidationResponse;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public final class ResponseSerializers {
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

  public static JsonValue serializeValueSchema(final ValueSchema schema) {
    if (schema == null) return JsonValue.NULL;

    return new ValueSchemaJsonParser().unparse(schema);
  }

  public static JsonValue serializeParameters(final List<Parameter> parameters) {
    final var parameterMap = IntStream.range(0, parameters.size()).boxed()
        .collect(Collectors.toMap(i -> parameters.get(i).name(), i -> Pair.of(i, parameters.get(i))));

    return serializeMap(pair -> Json.createObjectBuilder()
              .add("schema", new ValueSchemaJsonParser().unparse(pair.getRight().schema()))
              .add("order", pair.getLeft())
              .build(),
            parameterMap);
  }

  public static JsonValue serializeValueSchemas(final Map<String, ValueSchema> schemas) {
    if (schemas == null) return JsonValue.NULL;

    final var builder = Json.createArrayBuilder();
    schemas.forEach((k, v) -> builder.add(Json.createObjectBuilder()
      .add("name", k)
      .add("schema", serializeValueSchema(v))));
    return builder.build();
  }

  public static JsonValue serializeSample(final Pair<Duration, SerializedValue> element) {
    if (element == null) return JsonValue.NULL;
    return Json
        .createObjectBuilder()
        .add("x", serializeDuration(element.getLeft()))
        .add("y", serializeArgument(element.getRight()))
        .build();
  }

  public static JsonValue serializeString(final String value) {
    if (value == null) return JsonValue.NULL;
    return Json.createValue(value);
  }

  public static JsonValue serializeStringList(final List<String> elements) {
    return serializeIterable(ResponseSerializers::serializeString, elements);
  }

  public static JsonValue serializeArgument(final SerializedValue parameter) {
    if (parameter == null) return JsonValue.NULL;
    return serializedValueP.unparse(parameter);
  }

  public static JsonValue serializeEffectiveArgumentMap(final Map<String, SerializedValue> fields) {
    return Json.createObjectBuilder()
       .add("success", JsonValue.TRUE)
       .add("arguments", serializeMap(ResponseSerializers::serializeArgument, fields))
       .build();
  }

  public static JsonValue serializeBulkEffectiveArgumentResponseList(final List<BulkEffectiveArgumentResponse> responses) {
    return serializeIterable(ResponseSerializers::serializeBulkEffectiveArgumentResponse, responses);
  }

  public static JsonValue serializeConstraintBulkEffectiveArgumentResponse(BulkConstraintEffectiveArgumentResponse response) {
    return switch (response) {
      case BulkConstraintEffectiveArgumentResponse.Success s ->
          Json.createObjectBuilder()
              .add("id", s.constraintId().id())
              .add("revision", s.constraintId().revision())
              .add("success", JsonValue.TRUE)
              .add("arguments",
                   serializeMap(
                       ResponseSerializers::serializeArgument,
                       s.effectiveArguments()))
              .build();
      case BulkConstraintEffectiveArgumentResponse.TypeFailure tf ->
          Json.createObjectBuilder()
              .add("id", tf.constraintId().id())
              .add("revision", tf.constraintId().revision())
              .add("success", JsonValue.FALSE)
              .add("errors", "Constraint is not procedural")
              .build();
      case BulkConstraintEffectiveArgumentResponse.InstantiationFailure inf ->
          Json.createObjectBuilder(
                  serializeInstantiationException(inf.ex()).asJsonObject())
              .add("success", JsonValue.FALSE)
              .add("id", inf.constraintId().id())
              .add("revision", inf.constraintId().revision())
              .build();
      case BulkConstraintEffectiveArgumentResponse.NoConstraintFailure ncf ->
          Json.createObjectBuilder()
              .add("success", JsonValue.FALSE)
              .add("id", ncf.constraintId().id())
              .add("revision", ncf.constraintId().revision())
              .add("errors", "There is no constraint with this id")
              .build();
      case BulkConstraintEffectiveArgumentResponse.ProcedureLoadFailure plf ->
          Json.createObjectBuilder()
              .add("success", JsonValue.FALSE)
              .add("id", plf.constraintId().id())
              .add("revision", plf.constraintId().revision())
              .add("errors", "Error when loading the procedure jar")
              .build();
    };
  }

  public static JsonValue serializeBulkEffectiveArgumentResponse(BulkEffectiveArgumentResponse response) {
    return switch (response) {
      case BulkEffectiveArgumentResponse.Success s ->
          Json.createObjectBuilder()
              .add("typeName", s.activity().getTypeName())
              .add("success", JsonValue.TRUE)
              .add("arguments",
                   serializeMap(
                       ResponseSerializers::serializeArgument,
                       s.activity().getArguments()))
              .build();
      case BulkEffectiveArgumentResponse.TypeFailure tf ->
          Json.createObjectBuilder()
              .add("typeName", tf.ex().activityTypeId)
              .add("success", JsonValue.FALSE)
              .add("errors", "No such activity type")
              .build();
      case BulkEffectiveArgumentResponse.InstantiationFailure inf ->
          Json.createObjectBuilder(serializeInstantiationException(inf.ex()).asJsonObject())
              .add("typeName", inf.ex().containerName)
              .build();
    };
  }

  public static JsonValue serializeBulkArgumentValidationResponse(BulkArgumentValidationResponse response) {
    return switch (response){
      case BulkArgumentValidationResponse.Success s ->
          Json.createObjectBuilder()
              .add("success", JsonValue.TRUE)
              .build();
      case BulkArgumentValidationResponse.Validation v ->
          Json.createObjectBuilder()
              .add("success", JsonValue.FALSE)
              .add("type", "VALIDATION_NOTICES")
              .add("errors", Json.createObjectBuilder()
                                 .add("validationNotices", serializeIterable(ResponseSerializers::serializeValidationNotice, v.notices()))
                                 .build())
              .build();
      case BulkArgumentValidationResponse.NoSuchActivityError e ->
          Json.createObjectBuilder()
              .add("success", JsonValue.FALSE)
              .add("type", "NO_SUCH_ACTIVITY_TYPE")
              .add("errors", Json.createObjectBuilder()
                                 .add("noSuchActivityError", new MerlinFormattedError(e.ex()).toJson())
                                 .build())
              .build();
      case BulkArgumentValidationResponse.InstantiationError f ->
          Json.createObjectBuilder(serializeInstantiationException(f.ex()).asJsonObject())
              .add("type", "INSTANTIATION_ERRORS")
              .build();
      case BulkArgumentValidationResponse.NoSuchMissionModelError m ->
          Json.createObjectBuilder()
              .add("success", JsonValue.FALSE)
              .add("type", "NO_SUCH_MISSION_MODEL")
              .add("errors", Json.createObjectBuilder()
                                 .add("noSuchMissionModelError", new MerlinFormattedError(m.ex()).toJson())
                                 .build())
              .build();
    };
  }

  public static JsonValue serializeCreatedDatasetId(final long datasetId) {
    return Json.createObjectBuilder()
        .add("datasetId", datasetId)
        .build();
  }

  public static JsonValue serializeCreatedSimulationDatasetId(final long simulationDatasetId) {
    return Json.createObjectBuilder()
        .add("simulationDatasetId", simulationDatasetId)
        .build();
  }

  private static JsonValue serializeUnconstructableActivityFailure(final MissionModelService.ActivityInstantiationFailure reason) {
    final var builder = Json.createObjectBuilder();
    return switch (reason) {
      case MissionModelService.ActivityInstantiationFailure.InstantiationFailure r ->
          builder.add("reason", serializeInstantiationException(r.ex())).build();
      case MissionModelService.ActivityInstantiationFailure.NoSuchActivityType r ->
          builder.add("reason", new MerlinFormattedError(r.ex()).toJson()).build();
    };
  }

  public static JsonValue serializeUnconstructableActivityFailures(final Map<ActivityDirectiveId, MissionModelService.ActivityInstantiationFailure> failures) {
    if (failures.isEmpty()) {
      return Json.createObjectBuilder()
        .add("success", JsonValue.TRUE)
        .build();
    }
    return Json.createObjectBuilder()
        .add("success", JsonValue.FALSE)
        .add("errors", serializeMap(
           ResponseSerializers::serializeUnconstructableActivityFailure,
               failures
                   .entrySet()
                   .stream()
                   .collect(
                       Collectors.toMap(e -> Long.toString(e.getKey().id()), Map.Entry::getValue))))
        .build();
  }

  public static JsonValue serializeResourceSamples(final Map<String, List<Pair<Duration, SerializedValue>>> resourceSamples) {
    return Json
        .createObjectBuilder()
        .add("resourceSamples", serializeMap(
            elements -> serializeIterable(ResponseSerializers::serializeSample, elements),
            resourceSamples))
        .build();
  }

  public static JsonValue serializeConstraintResults(final int requestId, final SortedMap<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> resultMap) {
    var results = resultMap.entrySet().stream().map(entry -> {

      final var constraint = entry.getKey();
      final var fallible = entry.getValue();

      if (fallible.isFailure()) {
        return Json.createObjectBuilder()
                   .add("success", JsonValue.FALSE)
                   .add("constraintId", constraint.constraintId())
                   .add("constraintInvocationId", constraint.invocationId())
                   .add("constraintName", constraint.name())
                   .add("constraintRevision", constraint.revision())
                   .add("errors", serializeConstraintErrors(fallible.getFailureOptional().orElse(List.of())))
                   .add("results", JsonValue.EMPTY_JSON_OBJECT)
                   .build();
      }

      // The Constraint didn't fail, but somehow has no value.
      if (fallible.getOptional().isEmpty()) {
        return Json.createObjectBuilder()
                   .add("success", JsonValue.FALSE)
                   .add("constraintId", constraint.constraintId())
                   .add("constraintInvocationId", constraint.invocationId())
                   .add("constraintName", constraint.name())
                   .add("constraintRevision", constraint.revision())
                   .add("errors", Json.createArrayBuilder().add(
                       Json.createObjectBuilder()
                           .add("message", "Internal error processing a constraint")
                           .add("stack", "")
                           .add("location", JsonValue.EMPTY_JSON_OBJECT)).build())
                   .add("results", JsonValue.EMPTY_JSON_OBJECT)
                   .build();
      }

      // successful runs
      var constraintResult = fallible.getOptional().get();
      return Json.createObjectBuilder()
                 .add("success", JsonValue.TRUE)
                 .add("constraintId", constraint.constraintId())
                 .add("constraintInvocationId", constraint.invocationId())
                 .add("constraintName", constraint.name())
                 .add("constraintRevision", constraint.revision())
                 .add("errors", JsonValue.EMPTY_JSON_ARRAY)
                 .add("results", constraintResult.toJSON())
                 .build();

    }).toList();

    final var resultsArrayBuilder = Json.createArrayBuilder();
    results.forEach(resultsArrayBuilder::add);

    return Json.createObjectBuilder()
               .add("success", JsonValue.TRUE)
               .add("requestId", requestId)
               .add("constraintsRun", resultsArrayBuilder)
               .build();
  }

  public static JsonValue serializeSimulationResultsResponse(final GetSimulationResultsAction.Response response) {
      return switch (response) {
          case GetSimulationResultsAction.Response.Pending r -> Json
                  .createObjectBuilder()
                  .add("status", "pending")
                  .add("simulationDatasetId", r.simulationDatasetId())
                  .build();
          case GetSimulationResultsAction.Response.Incomplete r -> Json
                  .createObjectBuilder()
                  .add("status", "incomplete")
                  .add("simulationDatasetId", r.simulationDatasetId())
                  .build();
          case GetSimulationResultsAction.Response.Failed r -> Json
                  .createObjectBuilder()
                  .add("status", "failed")
                  .add("simulationDatasetId", r.simulationDatasetId())
                  .add("reason", MerlinParsers.simulationFailureP.unparse(r.reason()))
                  .build();
          case GetSimulationResultsAction.Response.Complete r -> Json
                  .createObjectBuilder()
                  .add("status", "complete")
                  .add("simulationDatasetId", r.simulationDatasetId())
                  .build();
          case null -> throw new IllegalArgumentException("simulation results action response was null");
      };
  }

  public static JsonValue serializeDuration(final Duration timestamp) {
    return Json.createValue(timestamp.in(Duration.MICROSECONDS));
  }

  public static JsonValue serializeFailures(final List<String> failures) {
    if (!failures.isEmpty()) {
      return Json.createObjectBuilder()
                 .add("success", JsonValue.FALSE)
                 .add("errors", Json.createArrayBuilder(failures))
                 .build();
    } else {
      return Json.createObjectBuilder()
                 .add("success", JsonValue.TRUE)
                 .build();
    }
  }

  public static JsonValue serializeValidationNotices(final List<ValidationNotice> notices) {
    if (!notices.isEmpty()) {
      return Json.createObjectBuilder()
          .add("success", JsonValue.FALSE)
          .add("errors", serializeIterable(ResponseSerializers::serializeValidationNotice, notices))
          .build();
    } else {
      return Json.createObjectBuilder()
          .add("success", JsonValue.TRUE)
          .build();
}
  }

  private static JsonValue serializeValidationNotice(final ValidationNotice notice) {
    return Json.createObjectBuilder()
        .add("subjects", serializeStringList(notice.subjects()))
        .add("message", notice.message())
        .build();
  }

  public static JsonValue serializeInstantiationException(final InstantiationException ex) {
    return Json.createObjectBuilder()
        .add("success", JsonValue.FALSE)
        .add("errors", Json.createObjectBuilder()
            .add("extraneousArguments", serializeStringList(ex.extraneousArguments.stream().map(a -> a.parameterName()).toList()))
            .add("unconstructableArguments", serializeIterable(ResponseSerializers::serializeUnconstructableArgument, ex.unconstructableArguments))
            .add("missingArguments", serializeStringList(ex.missingArguments.stream().map(a -> a.parameterName()).toList()))
            .build())
        .add("arguments", serializeMap(ResponseSerializers::serializeArgument, ex.validArguments.stream().collect(Collectors.toMap(
             InstantiationException.ValidArgument::parameterName,
             InstantiationException.ValidArgument::serializedValue))))
        .build();
  }

  private static JsonValue serializeUnconstructableArgument(final InstantiationException.UnconstructableArgument argument) {
    return Json.createObjectBuilder()
       .add("name", argument.parameterName())
       .add("failure", argument.failure())
       .build();
  }

  public static JsonValue serializeConstraintErrors(final List<? extends Exception> errors) {
    final var failureArrayBuilder = Json.createArrayBuilder();
    for (final var e : errors) {
      final JsonObjectBuilder errorBuilder = Json.createObjectBuilder();
      // failure was a compilation error
      if (e instanceof ConstraintsCompilationError compilationError) {
        errorBuilder.add("stack", compilationError.getStack())
                    .add("message", compilationError.getMessage())
                    .add("location", Json.createObjectBuilder()
                                         .add("line", compilationError.getLocation().line())
                                         .add("column", compilationError.getLocation().column()));
      }
      // failure was a captured runtime exception
      else {
        errorBuilder.add("message", e.getMessage())
                    .add("stack", Arrays.toString(e.getStackTrace()))
                    .add("location", JsonValue.EMPTY_JSON_OBJECT);
      }
      failureArrayBuilder.add(errorBuilder);
    }
    return failureArrayBuilder.build();
  }
}
