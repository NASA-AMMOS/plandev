package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Filter in windows that have another window preceding separated at least by delay
 */

// delay = 4
// passW:  ......[======]...[=======]............[=====].......
// output: ......[======]........................[=====].......
public class FilterSequenceMinGapBefore implements TimeWindowsFilter {

  private final Duration delay;

  public FilterSequenceMinGapBefore(final Duration delay) {
    this.delay = delay;
  }

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Interval before = null;
    var result = windows;
    for (final var interval: windows.iterateEqualTo(true)) {
      if (before != null) {
        if (interval.start.minus(before.end).compareTo(delay) < 0) {
          result = result.set(interval, false);
        }
      }
      before = interval;
    }
    return result;
  }


}
