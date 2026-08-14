package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

/**
 * Filter keeping windows with a duration superior or equal to a defined minimum duration
 */
public class FilterMinDuration extends FilterFunctional {
  private final Duration minDuration;

  public FilterMinDuration(final Duration filterByDuration) {
    this.minDuration = filterByDuration;
  }

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Windows result = windows;
    result = result.filterByDuration(this.minDuration, Duration.MAX_VALUE);
    return result;
  }

  @Override
  public boolean shouldKeep(final SimulationResults simulationResults, final Plan plan, final Interval range) {
    return range.duration().noShorterThan(minDuration);
  }

}
