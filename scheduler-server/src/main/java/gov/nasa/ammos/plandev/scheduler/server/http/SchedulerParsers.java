package gov.nasa.ammos.plandev.scheduler.server.http;

import gov.nasa.ammos.plandev.json.JsonParser;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.scheduler.server.models.HasuraAction;
import gov.nasa.ammos.plandev.scheduler.server.models.PlanId;
import gov.nasa.ammos.plandev.scheduler.server.models.SpecificationId;
import gov.nasa.ammos.plandev.scheduler.server.services.ScheduleFailure;
import gov.nasa.ammos.plandev.types.MissionModelId;
import gov.nasa.ammos.plandev.types.Timestamp;

import javax.json.Json;
import javax.json.stream.JsonParsingException;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;

import static gov.nasa.ammos.plandev.json.BasicParsers.anyP;
import static gov.nasa.ammos.plandev.json.BasicParsers.listP;
import static gov.nasa.ammos.plandev.json.BasicParsers.longP;
import static gov.nasa.ammos.plandev.json.BasicParsers.mapP;
import static gov.nasa.ammos.plandev.json.BasicParsers.nullableP;
import static gov.nasa.ammos.plandev.json.BasicParsers.productP;
import static gov.nasa.ammos.plandev.json.BasicParsers.stringP;
import static gov.nasa.ammos.plandev.json.Uncurry.tuple;
import static gov.nasa.ammos.plandev.json.Uncurry.untuple;
import static gov.nasa.ammos.plandev.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.PostgresParsers.pgTimestampP;

/**
 * json parsers for data objects used in the scheduler service endpoints
 */
public final class SchedulerParsers {
  private SchedulerParsers() {}

  //TODO: unify common private parsers between services (eg hasura details copied from MerlinParsers)

  public static final JsonParser<SpecificationId> specificationIdP
      = longP
      . map(
          SpecificationId::new,
          SpecificationId::id);

  public static final JsonParser<MissionModelId> missionModelIdP
      = longP
      . map(
          MissionModelId::new,
          MissionModelId::id);
  public static final JsonParser<PlanId> planIdP
      = longP
      . map(
          PlanId::new,
          PlanId::id);

  public static JsonParser<HasuraAction<HasuraAction.HasuraBulkEffectiveArguments>> hasuraBulkProcedureArgumentsP() {
    return hasuraActionF(
        productP
        .field("arguments", listP(procedureArgumentsP()))
        .map(
            untuple((items) -> new HasuraAction.HasuraBulkEffectiveArguments(items)),
            procedureArguments -> tuple(procedureArguments.items())));
  }

  public static JsonParser<ProcedureArguments> procedureArgumentsP() {
    return productP
        .field("id", longP)
        .field("revision", longP)
        .field("arguments", mapP(serializedValueP))
        .map(
            untuple((id, revision, arguments) -> new ProcedureArguments(new GoalId(id, revision), arguments)),
            procedureArguments -> tuple(procedureArguments.goalId().id(), procedureArguments.goalId().revision(), procedureArguments.arguments()));
  }

  public static final JsonParser<ScheduleFailure> scheduleFailureP = productP
      .field("type", stringP)
      .field("message", stringP)
      .field("data", anyP)
      .optionalField("trace", stringP)
      .field("timestamp", pgTimestampP)
      .map(
          untuple((type, message, data, trace, timestamp) -> new ScheduleFailure(type, message, data, trace.orElse(""), timestamp.toInstant())),
          failure -> tuple(failure.type(), failure.message(), failure.data(), Optional.ofNullable(failure.trace()), new Timestamp(failure.timestamp()))
      );

  /**
   * parser for hasura session details
   */
  private static final JsonParser<HasuraAction.Session> hasuraActionSessionP = productP
      .field("x-hasura-role", stringP)
      .optionalField("x-hasura-user-id", stringP)
      .map(
          untuple((role, userId) -> new HasuraAction.Session(role, userId.orElse(null))),
          session -> tuple(session.hasuraRole(), Optional.ofNullable(session.hasuraUserId())));

  /**
   * creates a parser fragment for a  general hasura action with name / session details that also takes an input arg
   *
   * the parser fragment extracts a tuple of the form (actionName, input, session) which can then be .map()ed in order
   * to establish the object semantics with the matching HasuraAction
   *
   * @param inputP the parser for the input to the hasura action
   * @param <I> the data type of the input to the hasura action
   * @return a parser that accepts hasura action / session details along with specified input type into a tuple
   *     of the form (name,input,session) ready for application of a mapping
   */
  private static <I extends HasuraAction.Input> JsonParser<HasuraAction<I>> hasuraActionF(final JsonParser<I> inputP) {
    return productP
        .field("action", productP.field("name", stringP))
        .field("input", inputP)
        .field("session_variables", hasuraActionSessionP)
        .field("request_query", stringP)
        .map(
            untuple((name, input, session, requestQuery) -> new HasuraAction<>(name, input, session)),
            $ -> tuple($.name(), $.input(), $.session(), ""));
  }

  /**
   * parser for a hasura action that accepts a plan id as its sole input, along with normal hasura session details
   */
  public static final JsonParser<HasuraAction<HasuraAction.SpecificationInput>> hasuraSpecificationActionP
      = hasuraActionF(productP.field("specificationId", specificationIdP)
      .map(
          untuple(HasuraAction.SpecificationInput::new),
          HasuraAction.SpecificationInput::specificationId));

  /**
   * parser for a hasura action that accepts a mission model id as its sole input, along with normal hasura session details
   */
  public static final JsonParser<HasuraAction<HasuraAction.MissionModelIdInput>> hasuraSchedulingDSLTypescriptActionP
      = hasuraActionF(productP
                          .field("missionModelId", missionModelIdP)
                          .optionalField("planId", nullableP(planIdP))
      .map(
          untuple((missionModelId, planId) -> new HasuraAction.MissionModelIdInput(missionModelId, planId.flatMap($->$))),
          input -> tuple(input.missionModelId(), Optional.of(input.planId()))));

  public static final JsonParser<HasuraAction.HasuraSchedulingGoalEvent> hasuraSchedulingGoalEventTriggerP
      = productP
      .field("event", productP
          .field("data", productP
              .field("new", productP
                  .field("goal_id", longP)
                  .field("revision", longP)
                  .rest())
              .rest())
          .rest())
      .rest()
      .map(
          untuple(HasuraAction.HasuraSchedulingGoalEvent::new),
          $ -> tuple($.goalId(), $.revision()));

  public static <T> T parseJson(final String jsonStr, final JsonParser<T> parser)
  throws JsonParsingException, InvalidJsonEntityException
  {
    try (final var reader = Json.createReader(new StringReader(jsonStr))) {
      final var requestJson = reader.readValue();
      final var result = parser.parse(requestJson);
      return result.getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)));
    }
  }
}
