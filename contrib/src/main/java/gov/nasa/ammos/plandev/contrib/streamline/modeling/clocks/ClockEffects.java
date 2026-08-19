package gov.nasa.ammos.plandev.contrib.streamline.modeling.clocks;

import gov.nasa.ammos.plandev.contrib.streamline.core.MutableResource;

import static gov.nasa.ammos.plandev.contrib.streamline.core.monads.DynamicsMonad.effect;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.clocks.Clock.clock;
import static gov.nasa.ammos.plandev.merlin.protocol.types.Duration.ZERO;

public final class ClockEffects {
  private ClockEffects() {}

  /**
   * Reset clock to zero elapsed time.
   */
  public static void restart(MutableResource<Clock> stopwatch) {
    stopwatch.emit("Restart", effect(c -> clock(ZERO)));
  }
}
