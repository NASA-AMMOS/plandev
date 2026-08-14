package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.types.Timestamp;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Map;

import static gov.nasa.jpl.aerie.json.BasicParsers.mapP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public final class PostgresParsers {

  public static final JsonParser<Timestamp> pgTimestampP = new JsonParser<>() {
    private static final DateTimeFormatter format =
        new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
            .toFormatter();

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
      if (result instanceof final JsonParseResult.Success<String> s) {
        try {
          final var instant = LocalDateTime.parse(s.result(), format).atZone(ZoneOffset.UTC);
          return JsonParseResult.success(new Timestamp(instant));
        } catch (final DateTimeParseException e) {
          return JsonParseResult.failure("invalid timestamp format "+e);
        }
      } else if (result instanceof final JsonParseResult.Failure<?> f) {
        return f.cast();
      } else {
        throw new UnexpectedSubtypeError(JsonParseResult.class, result);
      }
    }

    @Override
    public JsonValue unparse(final Timestamp value) {
      final var s = format.format(value.toInstant().atZone(ZoneOffset.UTC));
      return stringP.unparse(s);
    }
  };

  //TODO: serializedValueP is NOT safe to use here because used for parsing: subject to int/double typing confusion
  public static final JsonParser<Map<String, SerializedValue>> simulationArgumentsP = mapP(serializedValueP);
}
