package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.negate;

public class SimulationException extends RuntimeException {
  // This builder must be used to get optional subsecond values
  // See: https://stackoverflow.com/questions/30090710/java-8-datetimeformatter-parsing-for-optional-fractional-seconds-of-varying-sign
  public static final DateTimeFormatter format =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-DDD'T'HH:mm:ss")
          .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
          .toFormatter();

  public final Duration elapsedTime;
  public final Instant instant;
  public final Throwable cause;
  public final Optional<ActivityDirectiveId> directiveId;
  public final Optional<String> activityType;
  public final Optional<String> activityStackTrace;

  public SimulationException(final Duration elapsedTime, final Instant startTime, final Throwable cause) {
    super("Exception occurred " + formatDuration(elapsedTime) + " into the simulation at " + formatInstant(addDurationToInstant(startTime, elapsedTime)), cause);
    this.directiveId = Optional.empty();
    this.activityType = Optional.empty();
    this.activityStackTrace = Optional.empty();
    this.elapsedTime = elapsedTime;
    this.instant = addDurationToInstant(startTime, elapsedTime);
    this.cause = cause;
  }

  public SimulationException(
      final Duration elapsedTime,
      final Instant startTime,
      final ActivityDirectiveId directiveId,
      final List<SerializedActivity> activityStackTrace,
      final Throwable cause) {
    super("Exception occurred " + formatDuration(elapsedTime)
            + " into the simulation at " + formatInstant(addDurationToInstant(startTime, elapsedTime))
            + " while simulating activity directive with id " +directiveId.id(), cause);
    this.directiveId = Optional.of(directiveId);
    this.activityType = activityStackTrace.isEmpty() ? Optional.empty() : Optional.of(activityStackTrace.getFirst().getTypeName());
    this.activityStackTrace = activityStackTrace.isEmpty() ? Optional.empty(): Optional.of(activityStackTrace.stream().map( serializedActivity -> {
        final var index = activityStackTrace.indexOf(serializedActivity);
        return (index > 0 ? "|" : "") +"-".repeat(index) + serializedActivity.getTypeName();
      }).collect(Collectors.joining("\n")));
    this.elapsedTime = elapsedTime;
    this.instant = addDurationToInstant(startTime, elapsedTime);
    this.cause = cause;
  }

  public static String formatDuration(final Duration duration) {
    final var sign = (duration.isNegative()) ? "-" : "";
    var rest = duration;
    final long hours;
    if (duration.isNegative()) {
      hours = -rest.dividedBy(HOUR);
      rest = negate(rest.remainderOf(HOUR));
    } else {
      hours = rest.dividedBy(HOUR);
      rest = rest.remainderOf(HOUR);
    }

    final var minutes = rest.dividedBy(MINUTE);
    rest = rest.remainderOf(MINUTE);

    final var seconds = rest.dividedBy(SECOND);
    rest = rest.remainderOf(SECOND);

    final var microseconds = rest.dividedBy(MICROSECOND);

    return String.format("%s%02d:%02d:%02d.%06d", sign, hours, minutes, seconds, microseconds);
  }

  public static String formatInstant(final Instant instant) {
    return format.format(instant.atZone(ZoneOffset.UTC));
  }

  private static Instant addDurationToInstant(final Instant instant, final Duration duration) {
    return instant
        .plusSeconds(duration.in(Duration.SECONDS))
        .plusNanos(duration
                       .remainderOf(Duration.SECONDS)
                       .in(Duration.MICROSECONDS) * 1000);
  }
}
