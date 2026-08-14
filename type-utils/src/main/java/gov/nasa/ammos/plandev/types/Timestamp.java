package gov.nasa.jpl.aerie.types;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;

public record Timestamp(ZonedDateTime time) {
  // This builder must be used to get optional subsecond values
  // See: https://stackoverflow.com/questions/30090710/java-8-datetimeformatter-parsing-for-optional-fractional-seconds-of-varying-sign
  public static final DateTimeFormatter format =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-DDD'T'HH:mm:ss")
          .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
          .toFormatter();

  private Timestamp(String timestamp) throws DateTimeParseException {
    this(LocalDateTime.parse(timestamp, format).atZone(ZoneOffset.UTC));
  }

  public Timestamp(Instant instant) {
    this(instant.atZone(ZoneOffset.UTC));
  }

  public static Timestamp fromString(String timestamp) throws DateTimeParseException {
    return new Timestamp(timestamp);
  }

  public Timestamp plusMicros(final long micros) {
    return new Timestamp(this.time.plus(micros, ChronoUnit.MICROS));
  }

  public long microsUntil(final Timestamp other) {
    return this.time.until(other.time, ChronoUnit.MICROS);
  }

  public Instant toInstant() {
    return time.toInstant();
  }

  @Override
  public String toString() {
    return format.format(time);
  }
}
