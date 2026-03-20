package gov.nasa.jpl.aerie.scheduler.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSchedulingGoalException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleAction;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import org.apache.commons.lang3.tuple.Pair;

import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;

/**
 * json serialization methods for data entities used in the scheduler response bodies
 */
public class ResponseSerializers {

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

  /**
   * serialize the scheduler run result, including if it is incomplete/failed
   *
   * @param response the result of the scheduling run to serialize
   * @return a json serialization of the scheduling run result
   */
  public static JsonNode serializeScheduleResultsResponse(final ScheduleAction.Response response) {
    if (response instanceof final ScheduleAction.Response.Pending r) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("status", "pending");
      node.put("analysisId", r.analysisId());
      return node;
    } else if (response instanceof ScheduleAction.Response.Incomplete r) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("status", "incomplete");
      node.put("analysisId", r.analysisId());
      return node;
    } else if (response instanceof ScheduleAction.Response.Failed r) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("status", "failed");
      node.set("reason", SchedulerParsers.scheduleFailureP.unparse(r.reason()));
      node.put("analysisId", r.analysisId());
      return node;
    } else if (response instanceof ScheduleAction.Response.Complete r) {
      final var responseJson = JsonNodeFactory.instance.objectNode();
      responseJson.put("status", "complete");
      responseJson.set("results", serializeScheduleResults(r.results()));
      responseJson.put("analysisId", r.analysisId());
      if(r.datasetId().isPresent()){
        responseJson.put("datasetId", r.datasetId().get());
      }
      return responseJson;
    } else {
      throw new UnexpectedSubtypeError(ScheduleAction.Response.class, response);
    }
  }

  public static JsonNode serializeArgument(final SerializedValue parameter) {
    if (parameter == null) return NullNode.getInstance();
    return JsonEncoding.encode(parameter);
  }

  public static JsonNode serializeBulkEffectiveArgumentResponseList(final List<BulkEffectiveArgumentResponse> responses) {
    return serializeIterable(ResponseSerializers::serializeBulkEffectiveArgumentResponse, responses);
  }

  public static JsonNode serializeBulkEffectiveArgumentResponse(BulkEffectiveArgumentResponse response) {
    // TODO use pattern matching in switch statement with JDK 21
    if (response instanceof BulkEffectiveArgumentResponse.Success(
        GoalId goalId, Map<String, SerializedValue> effectiveArguments)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("id", goalId.id());
      node.put("revision", goalId.revision());
      node.set("success", BooleanNode.TRUE);
      node.set("arguments",
          serializeMap(
              ResponseSerializers::serializeArgument,
              effectiveArguments));
      return node;
    } else if (response instanceof BulkEffectiveArgumentResponse.TypeFailure(GoalId goalId)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.put("id", goalId.id());
      node.put("revision", goalId.revision());
      node.set("success", BooleanNode.FALSE);
      node.put("errors", "Goal is not procedural");
      return node;
    } else if (response instanceof BulkEffectiveArgumentResponse.InstantiationFailure(
        GoalId goalId,
        InstantiationException ex)) {
      final var baseNode = (com.fasterxml.jackson.databind.node.ObjectNode) serializeInstantiationException(ex);
      baseNode.put("id", goalId.id());
      baseNode.put("revision", goalId.revision());
      return baseNode;
    }
    else if (response instanceof BulkEffectiveArgumentResponse.NoGoalFailure(
        GoalId goalId,
        NoSuchSchedulingGoalException ex)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.set("success", BooleanNode.FALSE);
      node.put("id", goalId.id());
      node.put("revision", goalId.revision());
      node.put("errors", "There is no goal with this id");
      return node;
    }
    else if (response instanceof BulkEffectiveArgumentResponse.ProcedureLoadFailure(
       GoalId goalId,
       ProcedureLoader.ProcedureLoadException ex)) {
      final var node = JsonNodeFactory.instance.objectNode();
      node.set("success", BooleanNode.FALSE);
      node.put("id", goalId.id());
      node.put("revision", goalId.revision());
      node.put("errors", "Error when loading the procedure jar");
      return node;
    }
    final var node = JsonNodeFactory.instance.objectNode();
    node.set("success", BooleanNode.FALSE);
    node.put("errors", String.format("Internal error: %s", response));
    return node;
  }

  public static JsonNode serializeInstantiationException(final gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException ex) {
    final var errorsNode = JsonNodeFactory.instance.objectNode();
    errorsNode.set("extraneousArguments", serializeStringList(ex.extraneousArguments.stream().map(a -> a.parameterName()).toList()));
    errorsNode.set("unconstructableArguments", serializeIterable(ResponseSerializers::serializeUnconstructableArgument, ex.unconstructableArguments));
    errorsNode.set("missingArguments", serializeStringList(ex.missingArguments.stream().map(a -> a.parameterName()).toList()));

    final var node = JsonNodeFactory.instance.objectNode();
    node.set("success", BooleanNode.FALSE);
    node.set("errors", errorsNode);
    node.set("arguments", serializeMap(ResponseSerializers::serializeArgument, ex.validArguments.stream().collect(Collectors.toMap(
        gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException.ValidArgument::parameterName,
        InstantiationException.ValidArgument::serializedValue))));
    return node;
  }

  public static JsonNode serializeStringList(final List<String> elements) {
    return serializeIterable(ResponseSerializers::serializeString, elements);
  }

  public static JsonNode serializeString(final String value) {
    if (value == null) return NullNode.getInstance();
    return TextNode.valueOf(value);
  }

  private static JsonNode serializeUnconstructableArgument(
      final InstantiationException.UnconstructableArgument argument)
  {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("name", argument.parameterName());
    node.put("failure", argument.failure());
    return node;
  }

  /**
   * serialize the provided scheduling result summary to json
   *
   * @param results the scheduling results to serialize
   * @return a json serialization of the given scheduling result
   */
  public static JsonNode serializeScheduleResults(final ScheduleResults results)
  {
    return serializeMap(
        ResponseSerializers::serializeGoalResult,
        results.goalResults()
            .entrySet()
            .stream()
            .collect(
                Collectors.toMap(e -> Long.toString(e.getKey().goalInvocationId().get()), Map.Entry::getValue)));
  }

  private static JsonNode serializeGoalResult(final ScheduleResults.GoalResult goalResult) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.set("createdActivities", serializeIterable(
        id -> LongNode.valueOf(id.id()),
        goalResult.createdActivities()));
    node.set("satisfyingActivities", serializeIterable(
        id -> LongNode.valueOf(id.id()),
        goalResult.satisfyingActivities()));
    node.put("createdActivities", goalResult.satisfied());
    return node;
  }

  private static JsonNode serializeUserCodeError(final SchedulingCompilationError.UserCodeError error) {
    final var locationNode = JsonNodeFactory.instance.objectNode();
    locationNode.put("line", error.location().line());
    locationNode.put("column", error.location().column());

    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", error.message());
    node.put("stack", error.stack());
    node.set("location", locationNode);
    return node;
  }

  public static JsonNode serializeFailedGlobalSchedulingConditions(
      final List<List<SchedulingCompilationError.UserCodeError>> failedGlobalSchedulingConditions)
  {
    return serializeIterable(
        errors -> serializeIterable(ResponseSerializers::serializeUserCodeError, errors),
        failedGlobalSchedulingConditions);
  }

  public static JsonNode serializeFailedGoals(final List<Pair<GoalId, List<SchedulingCompilationError.UserCodeError>>> failedGoals) {
    return serializeIterable(
        goalFailures -> {
          final var node = JsonNodeFactory.instance.objectNode();
          node.put("goal_id", goalFailures.getKey().id());
          node.set("errors", serializeIterable(ResponseSerializers::serializeUserCodeError, goalFailures.getValue()));
          return node;
        },
        failedGoals);
  }

  public static JsonNode serializeNoSuchSpecificationException(final NoSuchSpecificationException e) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("specification_id", e.specificationId.id());
    return node;
  }

  public static JsonNode serializeNoSuchPlanException(final NoSuchPlanException e) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("specification_id", e.getInvalidPlanId().id());
    return node;
  }

  /**
   * create report of given exception that can be passed as json payload
   *
   * @param e the exception to generate json report for
   * @return a json serialization of the exception details
   */
  public static JsonNode serializeException(final Exception e) {
    //TODO: stack trace or other details back to ui / client?
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("message", e.toString());
    return node;
  }

  public static JsonNode serializeFailureReason(final JsonParseResult.FailureReason failure) {
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
        return LongNode.valueOf(i);
      }
    });
  }

  public static JsonNode serializeInvalidJsonException(final InvalidJsonException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "invalid-entity");
    node.put("message", "invalid json");
    return node;
  }

  public static JsonNode serializeInvalidEntityException(final InvalidEntityException ex) {
    final var node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "invalid-entity");
    node.set("failures", serializeIterable(ResponseSerializers::serializeFailureReason, ex.failures));
    return node;
  }

  public static JsonNode serializeValueSchema(final ValueSchema schema) {
    if (schema == null) return NullNode.getInstance();

    return new ValueSchemaJsonParser().unparse(schema);
  }
}
