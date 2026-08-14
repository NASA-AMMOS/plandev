package gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.TimeUtility;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * class allowing to define dynamic expressions of timepoints, relative to time anchors
 */
public abstract class TimeExpressionRelative {

  /**
   * A TimeExpressionRelative must implement this method
   *
   * @param plan the current plan
   * @param interval the range on which the relative time expression must be computed
   * @return a range of valid times satisfying the expression
   */
  public abstract Interval computeTime(final SimulationResults simulationResults, final Plan plan, final Interval interval);
  public abstract Optional<TimeAnchor> getAnchor();
  public abstract void extractResources(final Set<String> names);

  protected final List<Pair<TimeUtility.Operator, Duration>> operations = new ArrayList<>();


  public static TimeExpressionRelative fromAnchor(final TimeAnchor anchor) {
    return new TimeExpressionRelativeSimple(anchor, true);
  }

  public void addOperation(final TimeUtility.Operator operator, final Duration operand) {
    this.operations.add(Pair.of(operator, operand));
  }


  public static TimeExpressionRelative atStart() {
    return new TimeExpressionRelativeSimple(TimeAnchor.START, true);
  }

  public static TimeExpressionRelative offsetByAfterStart(final Duration dur) {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.START, true);
    te.operations.add(Pair.of(TimeUtility.Operator.PLUS, dur));
    return te;
  }


  public static TimeExpressionRelative offsetByBeforeStart(final Duration dur) {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.START, true);
    te.operations.add(Pair.of(TimeUtility.Operator.MINUS, dur));
    return te;
  }


  public static TimeExpressionRelative offsetByAfterEnd(final Duration dur) {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.END, true);
    te.operations.add(Pair.of(TimeUtility.Operator.PLUS, dur));
    return te;
  }


  public static TimeExpressionRelative offsetByBeforeEnd(final Duration dur) {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.END, true);
    te.operations.add(Pair.of(TimeUtility.Operator.MINUS, dur));
    return te;
  }

  public static TimeExpressionRelative beforeEnd() {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.END, false);
    te.operations.add(Pair.of(TimeUtility.Operator.MINUS, Duration.MAX_VALUE));
    return te;
  }

  public static TimeExpressionRelative beforeStart() {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.START, false);
    te.operations.add(Pair.of(TimeUtility.Operator.MINUS,Duration.MAX_VALUE));
    return te;
  }

  public static TimeExpressionRelative afterEnd() {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.END, false);
    te.operations.add(Pair.of(TimeUtility.Operator.PLUS, Duration.MAX_VALUE));
    return te;
  }

  public static TimeExpressionRelative afterStart() {
    final var te = new TimeExpressionRelativeSimple(TimeAnchor.START, false);
    te.operations.add(Pair.of(TimeUtility.Operator.PLUS, Duration.MAX_VALUE));
    return te;
  }

  public static TimeExpressionRelative endsBefore(TimeExpressionRelative expr){
    return new TimeExpressionRelativeBefore(expr, DEF_NAME);
  }

  public static final String DEF_NAME = "NO_NAME_TIME_EXPR";

  public static class Builder {
    private boolean interval = false;
    private String name = DEF_NAME;

    public Builder getThis() {
      return this;
    }


    public Builder name(final String name) {
      this.name = name;
      return getThis();
    }

    public Builder from(final TimeExpressionRelative otherExpr) {
      fromExpression = otherExpr;
      return getThis();
    }

    TimeExpressionRelative fromExpression;

    public Builder from(final TimeAnchor anchor) {
      fromAnchor = anchor;
      return getThis();
    }

    TimeAnchor fromAnchor;

    public Builder minus(final Duration dur) {
      operations.add(Pair.of(TimeUtility.Operator.MINUS, dur));
      return getThis();
    }

    public Builder interval() {
      this.interval = true;
      return getThis();
    }

    public Builder plus(final Duration dur) {
      operations.add(Pair.of(TimeUtility.Operator.PLUS, dur));
      return getThis();
    }

    protected final List<Pair<TimeUtility.Operator, Duration>> operations = new ArrayList<>();


  }

}
