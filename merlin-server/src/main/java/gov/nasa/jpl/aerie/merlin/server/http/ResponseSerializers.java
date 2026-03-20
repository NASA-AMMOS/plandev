package gov.nasa.jpl.aerie.merlin.server.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import gov.nasa.jpl.aerie.constraints.InputMismatchException;
import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.json.JsonParseResult.FailureReason;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintId;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.ValidationNotice;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintsCompilationError;
import gov.nasa.jpl.aerie.merlin.server.models.ProcedureLoader;
import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelAccessException;
import gov.nasa.jpl.aerie.merlin.server.services.BulkConstraintEffectiveArgumentResponse;
import gov.nasa.jpl.aerie.merlin.server.services.GetSimulationResultsAction;
import gov.nasa.jpl.aerie.merlin.server.services.LocalMissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.BulkEffectiveArgumentResponse;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.BulkArgumentValidationResponse;
import gov.nasa.jpl.aerie.merlin.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;

public final class ResponseSerializers {
  public static <T> JsonNode
  serializeIterable(final Function<T, JsonNode> elementSerializer, final Iterable<T> elements) {
    if (elements == null) return NullNode.getInstance();

    final var builder = JsonNodeFactory.instance.arrayNode();
    for (final var element : elements) builder.add(elementSerializer.apply(element));
    return builder;
  }

  public static <T> JsonNode serializeMap(final Function<T, JsonNode> fieldSerializer, final Map<String, T> fields) {
    if (fields == null) return NullNode.getInstance();

    final var builder = JsonNodeFactory.instance.objectNode();
    for (final var entry : fields.entrySet()) builder.set(entry.getKey(), fieldSerializer.apply(entry.getValue()));
    return builder;
  }

  public static JsonNode serializeValueSchema(final ValueSchema schema) {
    if (schema == null) return NullNode.getInstance();

    return new ValueSchemaJsonParser().unparse(schema);
  }

  public static JsonNode serializeParameters(final List<Parameter> parameters) {
    final var parameterMap = IntStream.range(0, parameters.size()).boxed()
        .collect(Collectors.toMap(i -> parameters.get(i).name(), i -> Pair.of(i, parameters.get(i))));

    return serializeMap(pair -> {
              final var node = JsonNodeFactory.instance.objectNode();
              node.set("schema", new ValueSchemaJsonParser().unparse(pair.getRight().schema()));
              node.put("order", pair.getLeft());
              return node;
            },
            parameterMap);
  }

  public static JsonNode serializeValueSchemas(final Map<String, ValueSchema> schemas) {
    if (schemas == null) return NullNode.getInstance();

    final var builder = JsonNodeFactory.instance.arrayNode();
    schemas.forEach((k, v) -> {
      final var entry = JsonNodeFactory.instance.objectNode();
      entry.put("name", k);
      entry.set("schema", serializeValueSchema(v));
      builder.add(entry);
    });
    return builder;
  }

  public static JsonNode serializeSample(final Pair<Duration, SerializedValue> element) {
    if (element == null) return NullNode.getInstance();
    final var node = JsonNodeFactory.instance.objectNode();
    node.set("x", serializeDuration(element.getLeft()));
    node.set("y", serializeArgument(element.getRight()));
    return node;
  }

  public static JsonNode serializeString(final String value) {
    if (value == null) return NullNode.getInstance();
    return TextNode.valueOf(value);
  }

  public static JsonNode serializeStringList(final List<String> elements) {
    return serializeIterable(ResponseSerializers::serializeString, elements);
  }

  public static JsonNode serializeArgument(final SerializedValue parameter) {
    if (parameter == null) return NullNode.getInstance();
    return JsonEncoding.encode(parameter);
  }

  public static JsonNode serializeEffectiveArgumentMap(final Map<String, SerializedValue> fields) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", true);
    node.set("arguments", serializeMap(ResponseSerializers::serializeArgument, fields));
    return node;
  }

  public static JsonNode serializeBulkEffectiveArgumentResponseList(final List<BulkEffectiveArgumentResponse> responses) {
    return serializeIterable(ResponseSerializers::serializeBulkEffectiveArgumentResponse, responses);
  }

  public static JsonNode serializeConstraintBulkEffectiveArgumentResponse(BulkConstraintEffectiveArgumentResponse response) {
    if (response instanceof BulkConstraintEffectiveArgumentResponse.Success(
        ConstraintId constraintId, Map<String, SerializedValue> effectiveArguments)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("id", constraintId.id());
      node.put("revision", constraintId.revision());
      node.put("success", true);
      node.set("arguments",
          serializeMap(
              ResponseSerializers::serializeArgument,
              effectiveArguments));
      return node;
    } else if (response instanceof BulkConstraintEffectiveArgumentResponse.TypeFailure(ConstraintId constraintId)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("id", constraintId.id());
      node.put("revision", constraintId.revision());
      node.put("success", false);
      node.put("errors", "Constraint is not procedural");
      return node;
    } else if (response instanceof BulkConstraintEffectiveArgumentResponse.InstantiationFailure(
        ConstraintId constraintId,
        InstantiationException ex)) {
      final var base = (ObjectNode) serializeInstantiationException(ex);
      base.put("success", false);
      base.put("id", constraintId.id());
      base.put("revision", constraintId.revision());
      return base;
    } else if (response instanceof BulkConstraintEffectiveArgumentResponse.NoConstraintFailure(ConstraintId constraintId)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.put("id", constraintId.id());
      node.put("revision", constraintId.revision());
      node.put("errors", "There is no constraint with this id");
      return node;
    } else if (response instanceof BulkConstraintEffectiveArgumentResponse.ProcedureLoadFailure(
        ConstraintId constraintId, ProcedureLoader.ProcedureLoadException ex)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.put("id", constraintId.id());
      node.put("revision", constraintId.revision());
      node.put("errors", "Error when loading the procedure jar");
      return node;
    }
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", false);
    node.put("errors", String.format("Internal error: %s", response));
    return node;
  }

  public static JsonNode serializeBulkEffectiveArgumentResponse(BulkEffectiveArgumentResponse response) {
    // TODO use pattern matching in switch statement with JDK 21
    if (response instanceof BulkEffectiveArgumentResponse.Success s) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("typeName", s.activity().getTypeName());
      node.put("success", true);
      node.set("arguments",
          serializeMap(
              ResponseSerializers::serializeArgument,
              s.activity().getArguments()));
      return node;
    } else if (response instanceof BulkEffectiveArgumentResponse.TypeFailure f) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("typeName", f.ex().activityTypeId);
      node.put("success", false);
      node.put("errors", "No such activity type");
      return node;
    } else if (response instanceof BulkEffectiveArgumentResponse.InstantiationFailure f) {
      final var base = (ObjectNode) serializeInstantiationException(f.ex());
      base.put("typeName", f.ex().containerName);
      return base;
    }
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", false);
    node.put("errors", String.format("Internal error: %s", response));
    return node;
  }

  public static JsonNode serializeBulkArgumentValidationResponse(BulkArgumentValidationResponse response) {
    // TODO use pattern matching in switch statement with JDK 21
    if (response instanceof BulkArgumentValidationResponse.Success) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", true);
      return node;
    } else if (response instanceof BulkArgumentValidationResponse.Validation v) {
      final var errors = JsonNodeFactory.instance.objectNode();
      errors.set("validationNotices", serializeIterable(ResponseSerializers::serializeValidationNotice, v.notices()));
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.put("type", "VALIDATION_NOTICES");
      node.set("errors", errors);
      return node;
    } else if (response instanceof BulkArgumentValidationResponse.NoSuchActivityError e) {
      final var errors = JsonNodeFactory.instance.objectNode();
      errors.set("noSuchActivityError", serializeNoSuchActivityTypeException(e.ex()));
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.put("type", "NO_SUCH_ACTIVITY_TYPE");
      node.set("errors", errors);
      return node;
    } else if (response instanceof BulkArgumentValidationResponse.InstantiationError f) {
      final var base = (ObjectNode) serializeInstantiationException(f.ex());
      base.put("type", "INSTANTIATION_ERRORS");
      return base;
    } else if (response instanceof BulkArgumentValidationResponse.NoSuchMissionModelError m) {
      final var errors = JsonNodeFactory.instance.objectNode();
      errors.set("noSuchMissionModelError", serializeNoSuchMissionModelException(m.ex()));
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.put("type", "NO_SUCH_MISSION_MODEL");
      node.set("errors", errors);
      return node;
    }

    // This should never happen, but we don't have exhaustive pattern matching
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", false);
    node.put("errors", String.format("Internal error: %s", response));
    return node;
  }

  public static JsonNode serializeCreatedDatasetId(final long datasetId) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("datasetId", datasetId);
    return node;
  }

  private static JsonNode serializeUnconstructableActivityFailure(final MissionModelService.ActivityInstantiationFailure reason) {
    // TODO use pattern-matching switch expression here when available with LTS
    final var builder = JsonNodeFactory.instance.objectNode();
    if (reason instanceof final MissionModelService.ActivityInstantiationFailure.InstantiationFailure r) {
      builder.set("reason", serializeInstantiationException(r.ex()));
      return builder;
    }
    else if (reason instanceof final MissionModelService.ActivityInstantiationFailure.NoSuchActivityType r) {
      builder.set("reason", serializeNoSuchActivityTypeException(r.ex()));
      return builder;
    }
    throw new UnexpectedSubtypeError(MissionModelService.ActivityInstantiationFailure.class, reason);
  }

  public static JsonNode serializeUnconstructableActivityFailures(final Map<ActivityDirectiveId, MissionModelService.ActivityInstantiationFailure> failures) {
    if (failures.isEmpty()) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", true);
      return node;
    }
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", false);
    node.set("errors", serializeMap(
       ResponseSerializers::serializeUnconstructableActivityFailure,
           failures
               .entrySet()
               .stream()
               .collect(
                   Collectors.toMap(e -> Long.toString(e.getKey().id()), Map.Entry::getValue))));
    return node;
  }

  public static JsonNode serializeResourceSamples(final Map<String, List<Pair<Duration, SerializedValue>>> resourceSamples) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.set("resourceSamples", serializeMap(
        elements -> serializeIterable(ResponseSerializers::serializeSample, elements),
        resourceSamples));
    return node;
  }

  public static JsonNode serializeConstraintResults(final int requestId, final Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> resultMap) {
    var results = resultMap.entrySet().stream().map(entry -> {

      final var constraint = entry.getKey();
      final var fallible = entry.getValue();

      if (fallible.isFailure()) {
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("success", false);
        node.put("constraintId", constraint.constraintId());
        node.put("constraintInvocationId", constraint.invocationId());
        node.put("constraintName", constraint.name());
        node.put("constraintRevision", constraint.revision());
        node.set("errors", serializeConstraintErrors(fallible.getFailureOptional().orElse(List.of())));
        node.set("results", JsonNodeFactory.instance.objectNode());
        return (JsonNode) node;
      }

      // The Constraint didn't fail, but somehow has no value.
      if (fallible.getOptional().isEmpty()) {
        final var errorEntry = JsonNodeFactory.instance.objectNode();
        errorEntry.put("message", "Internal error processing a constraint");
        errorEntry.put("stack", "");
        errorEntry.set("location", JsonNodeFactory.instance.objectNode());
        final var errorsArray = JsonNodeFactory.instance.arrayNode();
        errorsArray.add(errorEntry);
        final var node = JsonNodeFactory.instance.objectNode();
        node.put("success", false);
        node.put("constraintId", constraint.constraintId());
        node.put("constraintInvocationId", constraint.invocationId());
        node.put("constraintName", constraint.name());
        node.put("constraintRevision", constraint.revision());
        node.set("errors", errorsArray);
        node.set("results", JsonNodeFactory.instance.objectNode());
        return (JsonNode) node;
      }

      // successful runs
      var constraintResult = (ConstraintResult) fallible.getOptional().get();
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", true);
      node.put("constraintId", constraint.constraintId());
      node.put("constraintInvocationId", constraint.invocationId());
      node.put("constraintName", constraint.name());
      node.put("constraintRevision", constraint.revision());
      node.set("errors", JsonNodeFactory.instance.arrayNode());
      node.set("results", constraintResult.toJSON());
      return (JsonNode) node;

    }).toList();

    final var resultsArray = JsonNodeFactory.instance.arrayNode();
    results.forEach(resultsArray::add);

    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", true);
    node.put("requestId", requestId);
    node.set("constraintsRun", resultsArray);
    return node;
  }

  public static JsonNode serializeSimulationResultsResponse(final GetSimulationResultsAction.Response response) {
      return switch (response) {
          case GetSimulationResultsAction.Response.Pending r -> {
              final var node = JsonNodeFactory.instance.objectNode();
              node.put("status", "pending");
              node.put("simulationDatasetId", r.simulationDatasetId());
              yield node;
          }
          case GetSimulationResultsAction.Response.Incomplete r -> {
              final var node = JsonNodeFactory.instance.objectNode();
              node.put("status", "incomplete");
              node.put("simulationDatasetId", r.simulationDatasetId());
              yield node;
          }
          case GetSimulationResultsAction.Response.Failed r -> {
              final var node = JsonNodeFactory.instance.objectNode();
              node.put("status", "failed");
              node.put("simulationDatasetId", r.simulationDatasetId());
              node.set("reason", MerlinParsers.simulationFailureP.unparse(r.reason()));
              yield node;
          }
          case GetSimulationResultsAction.Response.Complete r -> {
              final var node = JsonNodeFactory.instance.objectNode();
              node.put("status", "complete");
              node.put("simulationDatasetId", r.simulationDatasetId());
              yield node;
          }
          case null -> throw new IllegalArgumentException("simulation results action response was null");
      };
  }

  public static JsonNode serializeDuration(final Duration timestamp) {
    return LongNode.valueOf(timestamp.in(Duration.MICROSECONDS));
  }

  public static JsonNode serializeFailures(final List<String> failures) {
    if (!failures.isEmpty()) {
      final var failuresArray = JsonNodeFactory.instance.arrayNode();
      for (final var f : failures) failuresArray.add(f);
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.set("errors", failuresArray);
      return node;
    } else {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", true);
      return node;
    }
  }

  public static JsonNode serializeValidationNotices(final List<ValidationNotice> notices) {
    if (!notices.isEmpty()) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", false);
      node.set("errors", serializeIterable(ResponseSerializers::serializeValidationNotice, notices));
      return node;
    } else {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("success", true);
      return node;
    }
  }

  private static JsonNode serializeValidationNotice(final ValidationNotice notice) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.set("subjects", serializeStringList(notice.subjects()));
    node.put("message", notice.message());
    return node;
  }

  public static JsonNode serializeInstantiationException(final InstantiationException ex) {
    final var errors = JsonNodeFactory.instance.objectNode();
    errors.set("extraneousArguments", serializeStringList(ex.extraneousArguments.stream().map(a -> a.parameterName()).toList()));
    errors.set("unconstructableArguments", serializeIterable(ResponseSerializers::serializeUnconstructableArgument, ex.unconstructableArguments));
    errors.set("missingArguments", serializeStringList(ex.missingArguments.stream().map(a -> a.parameterName()).toList()));
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("success", false);
    node.set("errors", errors);
    node.set("arguments", serializeMap(ResponseSerializers::serializeArgument, ex.validArguments.stream().collect(Collectors.toMap(
         InstantiationException.ValidArgument::parameterName,
         InstantiationException.ValidArgument::serializedValue))));
    return node;
  }

  private static JsonNode serializeUnconstructableArgument(
      final InstantiationException.UnconstructableArgument argument)
  {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("name", argument.parameterName());
    node.put("failure", argument.failure());
    return node;
  }

  public static JsonNode serializeJsonParsingException(final JsonProcessingException ex) {
    // TODO: Improve diagnostic information
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "invalid json");
    return node;
  }

  public static JsonNode serializeInvalidJsonException(final InvalidJsonException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "invalid-entity");
    node.put("message", "invalid json");
    return node;
  }

  public static JsonNode serializeConstraintErrors(final List<? extends Exception> errors) {
    final var failureArray = JsonNodeFactory.instance.arrayNode();
    for (final var e : errors) {
      final var errorNode = JsonNodeFactory.instance.objectNode();
      // failure was a compilation error
      if (e instanceof ConstraintsCompilationError compilationError) {
        final var locationNode = JsonNodeFactory.instance.objectNode();
        locationNode.put("line", compilationError.getLocation().line());
        locationNode.put("column", compilationError.getLocation().column());
        errorNode.put("stack", compilationError.getStack());
        errorNode.put("message", compilationError.getMessage());
        errorNode.set("location", locationNode);
      }
      // failure was a captured runtime exception
      else {
        errorNode.put("message", e.getMessage());
        errorNode.put("stack", Arrays.toString(e.getStackTrace()));
        errorNode.set("location", JsonNodeFactory.instance.objectNode());
      }
      failureArray.add(errorNode);
    }
    return failureArray;
  }

  public static JsonNode serializeInvalidEntityException(final InvalidEntityException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "invalid-entity");
    node.set("failures", serializeIterable(ResponseSerializers::serializeFailureReason, ex.failures));
    return node;
  }

  public static JsonNode serializeMissionModelLoadException(
      final LocalMissionModelService.MissionModelLoadException ex)
  {
    // TODO: Improve diagnostic information?
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", ex.getMessage());
    node.put("type", "Mission Model Load Failure");
    return node;
  }

  public static JsonNode serializeMissionModelAccessException(final MissionModelAccessException ex) {
    // TODO: Improve diagnostic information?
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", ex.getMessage());
    return node;
  }

  public static JsonNode serializeFailureReason(final FailureReason failure) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.set("breadcrumbs", serializeIterable(ResponseSerializers::serializeParseFailureBreadcrumb, failure.breadcrumbs()));
    node.put("message", failure.reason());
    return node;
  }

  public static JsonNode serializeParseFailureBreadcrumb(final gov.nasa.jpl.aerie.json.Breadcrumb breadcrumb) {
    return breadcrumb.visit(new gov.nasa.jpl.aerie.json.Breadcrumb.BreadcrumbVisitor<>() {
      @Override
      public JsonNode onString(final String s) {
        return TextNode.valueOf(s);
      }

      @Override
      public JsonNode onInteger(final Integer i) {
        return com.fasterxml.jackson.databind.node.IntNode.valueOf(i);
      }
    });
  }

  public static JsonNode serializeNoSuchPlanException(final NoSuchPlanException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "no such plan");
    node.put("plan_id", ex.id.id());
    return node;
  }

  public static JsonNode serializeNoSuchPlanDatasetException(final NoSuchPlanDatasetException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "no such plan dataset");
    node.put("plan_id", ex.id.id());
    return node;
  }

  public static JsonNode serializeNoSuchMissionModelException(final MissionModelService.NoSuchMissionModelException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "no such mission model");
    node.put("mission_model_id", ex.missionModelId.id());
    return node;
  }

  public static JsonNode serializeNoSuchActivityTypeException(final MissionModelService.NoSuchActivityTypeException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "no such activity type");
    node.put("activity_type", ex.activityTypeId);
    return node;
  }

  public static JsonNode serializeInputMismatchException(final InputMismatchException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "input mismatch exception");
    node.set("extensions", serializeCauseAsExtension(ex.getMessage()));
    return node;
  }

  public static JsonNode serializeSimulationDatasetMismatchException(final SimulationDatasetMismatchException ex){
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", "simulation dataset mismatch exception");
    node.set("extensions", serializeCauseAsExtension(ex.getMessage()));
    return node;
  }

  /**
   * Any exception that gets sent through a Hasura action needs to be wrapped in an "extensions" object to be
   * preserved in the response.
   * <a href="https://hasura.io/docs/2.0/actions/action-handlers/#returning-an-error-response">Reference</a>
   *
   * @param message
   * @return An ObjectNode that sets "cause" to the message.
   */
  public static ObjectNode serializeCauseAsExtension(String message) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("cause", message);
    return node;
  }
}
