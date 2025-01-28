package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.addToInstant;

/**
 * A variation on {@link Clock} that represents an absolute {@link Instant}
 * instead of a relative {@link Duration}.
 */
public record InstantClock(Instant extract) implements Dynamics<Instant, InstantClock> {
    @Override
    public InstantClock step(Duration t) {
        return new InstantClock(addToInstant(extract, t));
    }
}
