package gov.nasa.ammos.plandev.scheduler.constraints.transformers;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.Segment;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.scheduler.constraints.TimeRangeExpression;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

import java.util.List;

public class TransformerEnvelope implements TimeWindowsTransformer {

  private final List<TimeRangeExpression> insideExprs;


  public TransformerEnvelope(final List<TimeRangeExpression> insideExprs) {
    this.insideExprs = insideExprs;
  }

  @Override
  public Windows transformWindows(final Plan plan, final Windows windowsToTransform, final SimulationResults simulationResults) {

    Windows ret = new Windows(false);
    if(!windowsToTransform.stream().noneMatch(Segment::value)) {
      Duration min = windowsToTransform.maxTrueTimePoint().get().getKey(), max = windowsToTransform.minTrueTimePoint().get().getKey();
      boolean atLeastOne = false;
      for (var insideExpr : insideExprs) {

        var rangeExpr = insideExpr.computeRange(simulationResults, plan, windowsToTransform);
        if (!rangeExpr.stream().noneMatch(Segment::value)) {
          atLeastOne = true;
          min = Duration.min(min, rangeExpr.minTrueTimePoint().get().getKey());
          max = Duration.max(max, rangeExpr.maxTrueTimePoint().get().getKey());
        }
      }

      if (atLeastOne) {
        //register new transformed interval
        ret = ret.set(Interval.between(min, max), true);
      }
    }

    return ret;

  }
}
