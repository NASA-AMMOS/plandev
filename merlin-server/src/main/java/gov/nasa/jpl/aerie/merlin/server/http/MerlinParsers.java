package gov.nasa.jpl.aerie.merlin.server.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintId;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.Timestamp;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import static gov.nasa.jpl.aerie.json.BasicParsers.*;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.pgTimestampP;

public abstract class MerlinParsers {
  private MerlinParsers() {}

  public static final JsonParser<Timestamp> timestampP = new JsonParser<>() {
    @Override
    public ObjectNode getSchema(final SchemaCache anchors) {
      final var schema = stringP.getSchema(anchors);
      schema.put("format", "date-time");
      return schema;
    }

    @Override
    public JsonParseResult<Timestamp> parse(final JsonNode json) {
      final var result = stringP.parse(json);
      if (result instanceof JsonParseResult.Success<String> s) {
        try {
          return JsonParseResult.success(Timestamp.fromString(s.result()));
        } catch (DateTimeParseException e) {
          return JsonParseResult.failure("invalid timestamp format");
        }
      } else if (result instanceof JsonParseResult.Failure<?> f) {
        return f.cast();
      } else {
        throw new UnexpectedSubtypeError(JsonParseResult.class, result);
      }
    }

    @Override
    public JsonNode unparse(final Timestamp value) {
      return stringP.unparse(value.toString());
    }
  };

  public static final JsonParser<Duration> durationP
      = longP
      . map(
          microseconds -> Duration.of(microseconds, Duration.MICROSECONDS),
          duration -> duration.in(Duration.MICROSECONDS));

  public static final JsonParser<ActivityDirectiveId> activityDirectiveIdP
      = longP
      . map(
          ActivityDirectiveId::new,
          ActivityDirectiveId::id);

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

  public static final JsonParser<SimulationDatasetId> simulationDatasetIdP
      = longP
      . map(
          SimulationDatasetId::new,
          SimulationDatasetId::id);

  public static final JsonParser<DatasetId> datasetIdP
      = longP
      . map(
          DatasetId::new,
          DatasetId::id);

  public static final JsonParser<SimulationFailure> simulationFailureP = productP
      .field("type", stringP)
      .field("message", stringP)
      .field("data", anyP)
      .optionalField("trace", stringP)
      .field("timestamp", pgTimestampP)
      .map(
          untuple((type, message, data, trace, timestamp) -> new SimulationFailure(type, message, data, trace.orElse(""), timestamp.toInstant())),
          failure -> tuple(failure.type(), failure.message(), failure.data(), Optional.ofNullable(failure.trace()), new Timestamp(failure.timestamp()))
      );

  public static JsonParser<ConstraintId> constraintIdP() {
    return productP
        .field("constraint_id", longP)
        .field("revision", longP)
        .map(
            untuple(ConstraintId::new),
            constraintArguments -> tuple(constraintArguments.id(), constraintArguments.revision()));
  }


  public static <T> T parseJson(final String subject, final JsonParser<T> parser)
  throws InvalidJsonException, InvalidEntityException
  {
    try {
      final var mapper = new ObjectMapper();
      final var requestJson = mapper.readTree(subject);
      final var result = parser.parse(requestJson);
      return result.getSuccessOrThrow($ -> new InvalidEntityException(List.of($)));
    } catch (JsonProcessingException e) {
      throw new InvalidJsonException(e);
    }
  }

}
