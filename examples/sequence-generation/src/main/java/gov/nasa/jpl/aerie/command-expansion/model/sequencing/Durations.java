package gov.nasa.jpl.aerie.command_expansion.model.sequencing;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Durations {
    private Durations() {}

    public static Duration between(Instant start, Instant end) {
        return Duration.of(ChronoUnit.MICROS.between(start, end), Duration.MICROSECONDS);
    }
}
