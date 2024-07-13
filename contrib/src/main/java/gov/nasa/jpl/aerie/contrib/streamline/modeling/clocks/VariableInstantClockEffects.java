package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;

import java.time.Instant;

import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;

public final class VariableInstantClockEffects {
    private VariableInstantClockEffects() {}

    /**
     * Stop the clock without affecting the current time.
     */
    public static void pause(MutableResource<VariableInstantClock> clock) {
        clock.emit("Pause", effect($ -> new VariableInstantClock($.extract(), 0)));
    }

    /**
     * Start the clock without affecting the current time.
     */
    public static void start(MutableResource<VariableInstantClock> clock) {
        clock.emit("Start", effect($ -> new VariableInstantClock($.extract(), 1)));
    }

    /**
     * Reset the clock to the given time, without affecting how fast it's running.
     */
    public static void reset(MutableResource<VariableInstantClock> clock, Instant newTime) {
        clock.emit(name(effect($ -> new VariableInstantClock(newTime, $.multiplier())), "Reset to %s", newTime));
    }
}
