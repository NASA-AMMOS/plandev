package gov.nasa.jpl.aerie.scheduler.constraints;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.filters.Filters;
import gov.nasa.jpl.aerie.scheduler.constraints.filters.TimeWindowsFilter;
import gov.nasa.jpl.aerie.scheduler.constraints.transformers.TimeWindowsTransformer;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * A time range expression combines timewindows from states, activity expressions, state expressions, and other time
 * range expressions and allows to apply a sequence of filters and transforms to be used in goals
 */
public class TimeRangeExpression {


  /**
   * TODO: for now, acts like a big AND. We need a OR.
   *
   * @param plan x
   * @param domain x
   * @return x
   */
  public Windows computeRange(final SimulationResults simulationResults, final Plan plan, final Windows domain) {

    Windows inter = domain;
    if(inter.stream().noneMatch(Segment::value)) return inter;

    if (constantWin != null) {
      inter = inter.and(constantWin);
      if(inter.stream().noneMatch(Segment::value)) return inter;
    }

    if (actTemplate != null) {
      Windows actTw = new Windows(false);
      var minTimepoint = inter.minTrueTimePoint();
      var maxTimepoint = inter.maxTrueTimePoint();
      if(minTimepoint.isPresent() && maxTimepoint.isPresent()) {
        final var anchorActSearch = new ActivityExpression.Builder()
            .basedOn(actTemplate)
            //.startsIn(inter)
            .build(); //check if it exists IN the windows, not just the upper and lower bounds of the interval
        final var anchorActs = plan.find(anchorActSearch, simulationResults, new EvaluationEnvironment());
        for (var anchorAct : anchorActs) {
          var endInclusivity = Interval.Inclusivity.Exclusive;
          if(anchorAct.duration().isZero()){
            endInclusivity = Interval.Inclusivity.Inclusive;
          }
          actTw = actTw.set(Interval.between(anchorAct.duration(), Interval.Inclusivity.Inclusive, anchorAct.getEndTime(), endInclusivity), true);
        }
      }
      inter = inter.and(actTw);
      if(inter.stream().noneMatch(Segment::value)) return inter;
    }

    for (var otherExpr : timeRangeExpressions) {
      Windows windowsState = otherExpr.computeRange(simulationResults, plan, domain);
      inter = inter.and(windowsState);
      if(inter.stream().noneMatch(Segment::value)) return inter;
    }

    for (var expr : stateExpr) {
      final var domainOfInter = Interval.between(inter.minTrueTimePoint().get().getKey(), inter.maxTrueTimePoint().get().getKey());
      Windows windowsState = expr.evaluate(simulationResults, domainOfInter, new EvaluationEnvironment());
      inter = inter.and(windowsState);
      if(inter.stream().noneMatch(Segment::value)) return inter;
    }

    for (var constState : constantsStates) {
      final var domainOfInter = Interval.between(inter.minTrueTimePoint().get().getKey(), inter.maxTrueTimePoint().get().getKey());
      final var changePoints = simulationResults.discreteProfiles.get(constState).changePoints();

      // can't use non-final variables in lambdas, so its an array now.
      final Windows[] timeline = {new Windows(false)};


      final var container = new ArrayList<Interval>();
      changePoints.iterateEqualTo(true).iterator().forEachRemaining(container::add);
      container.stream().reduce((a, b) -> {
        timeline[0] = timeline[0].set(Interval.interval(a.start, Interval.Inclusivity.Exclusive, b.start, Interval.Inclusivity.Exclusive), true);
        return b;
      });
      inter = inter.and(timeline[0]);
      if(inter.stream().noneMatch(Segment::value)) return inter;
    }

    for (var filterOrTransform : filtersAndTransformers) {
      if (filterOrTransform instanceof TimeWindowsFilter timeWindowsFilter) {
        inter = timeWindowsFilter.filter(simulationResults, plan, inter);
      } else if (filterOrTransform instanceof TimeWindowsTransformer timeWindowsTransformer) {
        inter = timeWindowsTransformer.transformWindows(plan, inter, simulationResults);
      }
      if(inter.stream().noneMatch(Segment::value)) return inter;
    }

    return inter;
  }

  public void setName(String name) {
    this.name = name;
  }

  protected Windows constantWin;
  protected String name = "TRE_" + Math.abs(new Random().nextInt());
  protected List<TimeRangeExpression> timeRangeExpressions;
  protected List<Object> filtersAndTransformers;
  protected List<Expression<Windows>> stateExpr;
  protected List<String> constantsStates;
  private ActivityExpression actTemplate;

  public static TimeRangeExpression constantValuesOf(String discreteProfileName) {
    return new Builder().ofEachValue(discreteProfileName).build();
  }

  public static TimeRangeExpression of(Expression<Windows> sce) {
    return new Builder().from(sce).build();
  }

  public static TimeRangeExpression of(Windows wins) {
    return new Builder().from(wins).build();
  }

  public static class Builder {
    final List<Object> filtersAndTransformers = new ArrayList<>();
    final List<Expression<Windows>> stateExpr = new ArrayList<>();
    final List<String> constantsStates = new ArrayList<>();
    final List<TimeRangeExpression> timeRangeExpressions = new ArrayList<>();

    final List<Windows> constantWin = new ArrayList<>();

    private ActivityExpression actTemplate;


    public Builder thenFilter(TimeWindowsFilter filter) {
      filtersAndTransformers.add(filter);
      return getThis();
    }

    public Builder thenFilter(Function<Interval, Boolean> functionalFilter) {
      filtersAndTransformers.add(Filters.functionalFilter(functionalFilter));
      return getThis();
    }

    public Builder thenTransform(TimeWindowsTransformer transformer) {
      filtersAndTransformers.add(transformer);
      return getThis();
    }

    /**
     * state constraint use case
     *
     * @param expr x
     * @return x
     */
    public Builder from(Expression<Windows> expr) {
      this.stateExpr.add(expr);
      return getThis();
    }

    public Builder from(Windows constantWin) {
      this.constantWin.add(constantWin);
      return getThis();
    }

    public Builder from(TimeRangeExpression expr) {
      this.timeRangeExpressions.add(expr);
      return getThis();
    }

    public Builder from(ActivityExpression actTemplate) {
      this.actTemplate = actTemplate;
      return getThis();
    }

    String name;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * relative instant use case
     *
     * @param nameDiscreteProfile name of the discrete profile
     * @param <T> x
     * @return x
     */
    public <T> Builder ofEachValue(String nameDiscreteProfile) {
      this.constantsStates.add(nameDiscreteProfile);
      return getThis();
    }


    public TimeRangeExpression build() {
      TimeRangeExpression tre = new TimeRangeExpression();

      if (constantsStates.isEmpty()
          && stateExpr.isEmpty()
          && actTemplate == null
          && timeRangeExpressions.isEmpty()
          && constantWin.isEmpty()) {
        throw new RuntimeException("either from or constantValuesOf has to be used to build a valid expression");
      }
      tre.filtersAndTransformers = filtersAndTransformers;
      tre.constantsStates = constantsStates;
      tre.stateExpr = stateExpr;
      tre.timeRangeExpressions = timeRangeExpressions;
      tre.actTemplate = actTemplate;

      if (constantWin.size() > 0) {
        Windows cstWind = constantWin.get(0);
        for (var cstWin : constantWin) {
          cstWind = cstWind.and(cstWin);
        }
        tre.constantWin = cstWind;
      }

      if (name != null) {
        tre.name = name;
      }
      return tre;
    }

    public Builder getThis() {
      return this;
    }

  }


  private TimeRangeExpression() {

  }

}
