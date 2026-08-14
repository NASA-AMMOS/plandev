package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

public record VariableClock(Duration extract, int multiplier) implements Dynamics<Duration, VariableClock> {
  @Override
  public VariableClock step(final Duration t) {
    return new VariableClock(extract.plus(t.times(multiplier)), multiplier);
  }

  public static VariableClock runningStopwatch() {
    return runningStopwatch(ZERO);
  }

  public static VariableClock runningStopwatch(Duration time) {
    return new VariableClock(time, 1);
  }

  public static VariableClock pausedStopwatch() {
    return pausedStopwatch(ZERO);
  }

  public static VariableClock pausedStopwatch(Duration time) {
    return new VariableClock(time, 0);
  }

  public static VariableClock runningTimer(Duration timeRemaining) {
    return new VariableClock(timeRemaining, -1);
  }

  public static VariableClock pausedTimer(Duration timeRemaining) {
    // Identical to pausedStopwatch (could be condensed to just "paused", perhaps?)
    return new VariableClock(timeRemaining, 0);
  }
}
