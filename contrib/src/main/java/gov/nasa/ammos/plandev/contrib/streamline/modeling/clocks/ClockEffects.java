package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;

import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock.clock;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

public final class ClockEffects {
  private ClockEffects() {}

  /**
   * Reset clock to zero elapsed time.
   */
  public static void restart(MutableResource<Clock> stopwatch) {
    stopwatch.emit("Restart", effect(c -> clock(ZERO)));
  }
}
