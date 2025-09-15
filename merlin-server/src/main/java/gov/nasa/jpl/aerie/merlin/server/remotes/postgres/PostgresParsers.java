package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.json.JsonParseResult;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.SchemaCache;
import gov.nasa.jpl.aerie.json.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;
import org.postgresql.util.PGInterval;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Map;

import static gov.nasa.jpl.aerie.json.BasicParsers.*;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

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

  public static final JsonParser<Pair<String, ValueSchema>> discreteProfileTypeP =
      productP
          .field("type", literalP("discrete"))
          .field("schema", valueSchemaP)
          .map(
              untuple((type, schema) -> Pair.of("discrete", schema)),
              $ -> tuple(Unit.UNIT, $.getRight()));

  public static final JsonParser<Pair<String, ValueSchema>> realProfileTypeP =
      productP
          .field("type", literalP("real"))
          .field("schema", valueSchemaP)
          .map(
              untuple((type, schema) -> Pair.of("real", schema)),
              $ -> tuple(Unit.UNIT, $.getRight()));

  static final JsonParser<Pair<String, ValueSchema>> profileTypeP =
      chooseP(
          discreteProfileTypeP,
          realProfileTypeP);

  public static Duration parseOffset(final ResultSet resultSet, final int index) throws SQLException {
    return Duration.of(microsOfPGInterval(new PGInterval(resultSet.getString(index))), Duration.MICROSECONDS);
  }

  static long microsOfPGInterval(final PGInterval interval) {
    if (interval.getYears() != 0) {
      throw new IllegalArgumentException("Cannot convert years to microseconds");
    }
    if (interval.getMonths() != 0) {
      throw new IllegalArgumentException("Cannot convert months to microseconds");
    }
    return interval.getMicroSeconds() +
           (1_000_000L * interval.getWholeSeconds()) +
           (1_000_000L * 60 * interval.getMinutes()) +
           (1_000_000L * 3600 * interval.getHours()) +
           (1_000_000L * 3600 * 24 * interval.getDays());
  }

  public static Duration parseDurationISO8601(final String iso8601String){
    final var javaDuration = java.time.Duration.parse(iso8601String);
    return Duration.of((javaDuration.getSeconds() * 1_000_000L) + (javaDuration.getNano() / 1000L), Duration.MICROSECONDS);
  }

  public static final JsonParser<Map<String, SerializedValue>> constraintArgumentsP = mapP(serializedValueP);
  public static final JsonParser<Map<String, SerializedValue>> activityArgumentsP = mapP(serializedValueP);

  public static final JsonParser<Map<String, SerializedValue>> simulationArgumentsP = mapP(serializedValueP);

  public static final JsonParser<ActivityAttributesRecord> activityAttributesP = productP
      .optionalField("directiveId", longP)
      .field("arguments", activityArgumentsP)
      .optionalField("computedAttributes", serializedValueP)
        .map(
            untuple(ActivityAttributesRecord::new),
            $ -> tuple($.directiveId(), $.arguments(), $.computedAttributes()));

  public static final JsonParser<Map<String, Pair<Integer, ValueSchema>>> parameterRecordP =
      mapP(
          productP
              .field("order", intP)
              .field("schema", valueSchemaP));

  public static <V> JsonParseResult<V>
  getJsonColumn(final ResultSet results, final String column, final JsonParser<V> parser) throws SQLException {
    try (final var reader = Json.createReader(results.getCharacterStream(column))) {
      return parser.parse(reader.readValue());
    }
  }
}
