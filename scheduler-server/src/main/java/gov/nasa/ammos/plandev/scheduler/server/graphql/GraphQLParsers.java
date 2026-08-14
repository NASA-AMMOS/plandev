package gov.nasa.jpl.aerie.scheduler.server.graphql;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.json.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityAttributesRecord;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;
import org.postgresql.util.PGInterval;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static gov.nasa.jpl.aerie.json.BasicParsers.doubleP;
import static gov.nasa.jpl.aerie.json.BasicParsers.literalP;
import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.mapP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;

/**
 * utility methods for parsing graphql scalars as returned by the merlin interface
 */
//TODO: elevate to a merlin-level library to allow parsing in various modules (eg dupl in scheduler...DemuxJson)
public class GraphQLParsers {

  /**
   * the formatting expected in timestamptz scalars returned by graphql queries
   */
  //TODO: what about sub-seconds in plan start_time?
  //TODO: inconsistent with DOY format in Timestamp.fromString, MerlinParsers.timestampP, etc used in merlin-server
  public static final DateTimeFormatter timestampFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  /**
   * parse the given graphQL formatted timestamptz scalar string (eg 2021-01-01T00:00:00+00:00)
   *
   * @param in the input graphql formatted timestamptz scalar string to parse
   * @return the timestamp object represented by the input string
   */
  //TODO: unify with wherever this translation must already exist in merlin
  public static Timestamp parseGraphQLTimestamp(final String in) {
    return new Timestamp(ZonedDateTime.parse(in, timestampFormat).toInstant());
  }

  public static final JsonParser<Map<String, SerializedValue>> simulationArgumentsP = mapP(serializedValueP);

  public static final JsonParser<RealDynamics> realDynamicsP
      = productP
      . field("initial", doubleP)
      . field("rate", doubleP)
      . map(
          untuple(RealDynamics::linear),
          $ -> tuple($.initial, $.rate));

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


  public static final JsonParser<Map<String, SerializedValue>> activityArgumentsP = mapP(serializedValueP);

  public static final JsonParser<ActivityAttributesRecord> activityAttributesP = productP
      .optionalField("directiveId", longP)
      .field("arguments", activityArgumentsP)
      .optionalField("computedAttributes", serializedValueP)
      .map(
          untuple(ActivityAttributesRecord::new),
          $ -> tuple($.directiveId(), $.arguments(), $.computedAttributes()));

  public static final JsonParser<Duration> durationP =
      stringP
          .map(
              GraphQLParsers::durationFromPGInterval,
              duration -> graphQLIntervalFromDuration(duration).getValue());

  public static Duration durationFromPGInterval(final String pgInterval) {
    try {
      final PGInterval asInterval = new PGInterval(pgInterval);
      if(asInterval.getYears() != 0 ||
         asInterval.getMonths() != 0) throw new RuntimeException("Years or months found in a pginterval");
      final var asDuration = java.time.Duration.ofDays(asInterval.getDays())
                                               .plusHours(asInterval.getHours())
                                               .plusMinutes(asInterval.getMinutes())
                                               .plusSeconds(asInterval.getWholeSeconds())
                                               .plusNanos(asInterval.getMicroSeconds()*1000);
      return Duration.of(asDuration.toNanos()/1000, MICROSECONDS);
    }catch(SQLException e){
      throw new RuntimeException(e);
    }
  }

  public static PGInterval graphQLIntervalFromDuration(final Duration duration) {
    try {
      final var micros = duration.in(MICROSECONDS);
      return new PGInterval("PT%d.%06dS".formatted(micros / 1_000_000, micros % 1_000_000));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
  public static PGInterval graphQLIntervalFromDuration(final Instant instant1, final Instant instant2) {
    try {
      final var micros = java.time.Duration.between(instant1, instant2).toNanos() / 1000;
      return new PGInterval("PT%d.%06dS".formatted(micros / 1_000_000, micros % 1_000_000));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public static Instant instantFromStart(Instant start, Duration duration){
    return start.plus(java.time.Duration.of(duration.in(Duration.MICROSECONDS), ChronoUnit.MICROS));
  }

}
