package gov.nasa.jpl.plandev.scheduler.constraints.filters;

import gov.nasa.jpl.plandev.constraints.model.SimulationResults;
import gov.nasa.jpl.plandev.constraints.time.Interval;
import gov.nasa.jpl.plandev.scheduler.model.Plan;

import java.util.function.Function;

public class FilterUserFunctional extends FilterFunctional {

  final Function<Interval, Boolean> function;

  public FilterUserFunctional(final Function<Interval, Boolean> function) {
    this.function = function;
  }

  @Override
  public boolean shouldKeep(final SimulationResults simulationResults, final Plan plan, final Interval range) {
    return function.apply(range);
  }
}
