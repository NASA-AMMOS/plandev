package gov.nasa.ammos.plandev.scheduler.constraints.timeexpressions;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.scheduler.TimeUtility;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

import java.util.Set;
import java.util.Optional;

public class TimeExpressionRelativeSimple extends TimeExpressionRelative {
  protected final TimeAnchor anchor;
  protected boolean fixed = true;

  public TimeExpressionRelativeSimple(final TimeAnchor anchor, final boolean fixed) {
    this.anchor = anchor;
    this.fixed = fixed;
  }

  @Override
  public Interval computeTime(final SimulationResults simulationResults, final Plan plan, final Interval interval) {
    return computeTimeRelativeAbsolute(interval);
  }

  @Override
  public Optional<TimeAnchor> getAnchor() {
    return Optional.ofNullable(anchor);
  }

  public Interval computeTimeRelativeAbsolute(final Interval interval) {
    Duration from = null;
    if (anchor == TimeAnchor.START) {
      from = interval.start;
    } else if (anchor == TimeAnchor.END) {
      from = interval.end;
    }

    Duration res = from;
    for (final var entry : this.operations) {
      res = TimeUtility.performOperation(entry.getKey(), res, entry.getValue());
    }

    Interval retRange;

    //if we want an range of possibles
    if (!fixed) {
      if (res.compareTo(from) > 0) {
        retRange = Interval.between(from, res);

      } else {
        retRange = Interval.between(res, from);
      }
      // we just want to compute the absolute timepoint
    } else {
      retRange = Interval.between(res, res);
    }

    return retRange;
  }

  @Override
  public void extractResources(final Set<String> names) {}
}
