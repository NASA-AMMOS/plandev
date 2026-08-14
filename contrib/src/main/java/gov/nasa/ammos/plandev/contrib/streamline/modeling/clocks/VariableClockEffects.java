package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;

import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.VariableClock.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

public final class VariableClockEffects {
  private VariableClockEffects() {}

  /**
   * Stop the clock without affecting the current time.
   */
  public static void pause(MutableResource<VariableClock> stopwatch) {
    stopwatch.emit("Pause", effect(c -> pausedStopwatch(c.extract())));
  }

  /**
   * Start the clock without affecting the current time.
   */
  public static void start(MutableResource<VariableClock> stopwatch) {
    stopwatch.emit("Start", effect(c -> runningStopwatch(c.extract())));
  }

  /**
   * Stop the clock and reset the time to zero.
   */
  public static void reset(MutableResource<VariableClock> stopwatch) {
    stopwatch.emit("Reset", effect(c -> pausedStopwatch(ZERO)));
  }

  /**
   * Start the clock and reset the time to zero.
   */
  public static void restart(MutableResource<VariableClock> stopwatch) {
    stopwatch.emit("Restart", effect(c -> runningStopwatch(ZERO)));
  }

  /**
   * Start counting down from current value.
   */
  public static void startCountdown(MutableResource<VariableClock> timer) {
    timer.emit("Start Countdown", effect(c -> runningTimer(c.extract())));
  }
}
