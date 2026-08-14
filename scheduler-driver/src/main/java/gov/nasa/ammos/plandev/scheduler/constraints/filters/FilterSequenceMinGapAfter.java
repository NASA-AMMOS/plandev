package gov.nasa.ammos.plandev.scheduler.constraints.filters;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

/**
 * Filter windows that have at least another window preceding ending within a delay
 */
public class FilterSequenceMinGapAfter implements TimeWindowsFilter {

  private final Duration minDelay;
  public FilterSequenceMinGapAfter(final Duration minDelay) {
    this.minDelay = minDelay;
  }

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Interval before = null;
    var result = windows;
    for (final var interval: windows.iterateEqualTo(true)) {
      if (before != null) {
        if (interval.start.minus(before.end).compareTo(minDelay) < 0) {
          result = result.set(before, false);
        }
      }
      before = interval;
    }
    return result;
  }


}
