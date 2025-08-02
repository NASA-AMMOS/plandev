package gov.nasa.jpl.aerie.merlin.server.http;

import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.Timestamp;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;
import java.io.StringReader;
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
    public JsonObject getSchema(final SchemaCache anchors) {
      return Json
          .createObjectBuilder(stringP.getSchema())
          .add("format", "date-time")
          .build();
    }

    @Override
    public JsonParseResult<Timestamp> parse(final JsonValue json) {
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
    public JsonValue unparse(final Timestamp value) {
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

  public static <T> T parseJson(final String subject, final JsonParser<T> parser)
  throws InvalidJsonException, InvalidEntityException
  {
    try {
      final var requestJson = Json.createReader(new StringReader(subject)).readValue();
      final var result = parser.parse(requestJson);
      return result.getSuccessOrThrow($ -> new InvalidEntityException(List.of($)));
    } catch (JsonParsingException e) {
      throw new InvalidJsonException(e);
    }
  }

}
