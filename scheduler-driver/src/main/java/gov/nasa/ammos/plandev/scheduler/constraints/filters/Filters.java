package gov.nasa.jpl.aerie.scheduler.constraints.filters;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.TimeRangeExpression;

import java.util.function.Function;

//directory class from which the user can create filters
public class Filters {
  public static TimeWindowsFilter withinEach(final TimeRangeExpression expr, final TimeWindowsFilter filter) {
    return new FilterWithReset(expr, filter);
  }

  public static class LatchingBuilder {

    FilterFunctional filter1;
    FilterFunctional filter2;
    TimeRangeExpression expr;

    public LatchingBuilder withinEach(TimeRangeExpression expr) {
      this.expr = expr;
      return this;
    }

    public LatchingBuilder filterFirstBy(FilterFunctional filter) {
      filter1 = filter;
      return this;
    }

    public LatchingBuilder thenFilterBy(FilterFunctional filter) {
      filter2 = filter;
      return this;
    }

    public TimeWindowsFilter build() {
      return Filters.withinEach(expr, new FilterLatching(filter1, filter2));
    }


  }


  public static FilterElementSequence last() {
    return FilterElementSequence.last();
  }


  public static FilterElementSequence first() {
    return FilterElementSequence.first();
  }


  public static FilterElementSequence numbered(int i) {
    return FilterElementSequence.numbered(i);
  }

  public static FilterFunctional alwaysSatisfied(Expression<Windows> expr) {
    return new FilterAlwaysSatisfied(expr);
  }


  public static FilterFunctional everViolated(Expression<Windows> expr) {
    return new FilterEverViolated(expr);
  }

  public static FilterFunctional minDuration(Duration dur) {
    return new FilterMinDuration(dur);
  }

  public static FilterFunctional maxDuration(Duration dur) {
    return new FilterMaxDuration(dur);
  }

  public static TimeWindowsFilter minGapBefore(Duration dur) {
    return new FilterSequenceMinGapBefore(dur);
  }

  public static TimeWindowsFilter minGapAfter(Duration dur) {
    return new FilterSequenceMinGapAfter(dur);

  }

  public static TimeWindowsFilter maxGapBefore(Duration dur) {
    return new FilterSequenceMaxGapBefore(dur);

  }

  public static TimeWindowsFilter maxGapAfter(Duration dur) {
    return new FilterSequenceMaxGapAfter(dur);

  }

  public static TimeWindowsFilter functionalFilter(Function<Interval, Boolean> function) {
    return new FilterUserFunctional(function);
  }


}
