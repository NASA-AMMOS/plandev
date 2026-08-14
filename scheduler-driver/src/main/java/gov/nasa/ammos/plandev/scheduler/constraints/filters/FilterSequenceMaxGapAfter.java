package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Filter windows that have at least another window preceding ending within a delay
 */
public class FilterSequenceMaxGapAfter implements TimeWindowsFilter {

  private final Duration maxDelay;

  public FilterSequenceMaxGapAfter(final Duration maxDelay) {
    this.maxDelay = maxDelay;
  }

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Interval before = null;
    var result = windows;
    for (var interval : windows.iterateEqualTo(true)) {
      if (before != null) {
        if (interval.start.minus(before.end).compareTo(maxDelay) > 0) {
          result = result.set(before, false);
        }
      }
      before = interval;
    }
    result = result.removeTrueSegment(-1);
    return result;
  }


}
