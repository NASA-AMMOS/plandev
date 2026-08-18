package gov.nasa.ammos.plandev.scheduler.constraints.filters;


import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

/**
 * Filter keeping windows with a duration inferior or equal to a defined minimum duration
 */
public class FilterMaxDuration extends FilterFunctional {
  private final Duration maxDuration;

  public FilterMaxDuration(final Duration filterByDuration) {
    this.maxDuration = filterByDuration;
  }

  @Override
  public Windows filter(final SimulationResults simulationResults, final Plan plan, final Windows windows) {
    Windows result = windows;
    result = result.filterByDuration(Duration.ZERO, this.maxDuration);
    return result;
  }


  @Override
  public boolean shouldKeep(final SimulationResults simulationResults, final Plan plan, final Interval range) {
    return range.duration().noLongerThan(maxDuration);
  }
}
