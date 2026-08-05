package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.addToInstant;

/**
 * A variation on {@link VariableClock} that represents an absolute {@link Instant}
 * instead of a relative {@link Duration}.
 */
public record VariableInstantClock(Instant extract, int multiplier) implements Dynamics<Instant, VariableInstantClock> {
    @Override
    public VariableInstantClock step(Duration t) {
        return new VariableInstantClock(addToInstant(extract, t.times(multiplier)), multiplier);
    }
}
