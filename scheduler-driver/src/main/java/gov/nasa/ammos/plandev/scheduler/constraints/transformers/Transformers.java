package gov.nasa.jpl.aerie.scheduler.constraints.transformers;

import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.TimeRangeExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import java.util.ArrayList;
import java.util.List;

public class Transformers {

  public static TimeWindowsTransformer beforeEach() {
    return new TransformerBeforeEach(Duration.ZERO);
  }


  public static TimeWindowsTransformer afterEach(final Duration dur) {
    return new TransformerAfterEach(dur);
  }

  public static TimeWindowsTransformer beforeEach(final Duration dur) {
    return new TransformerBeforeEach(dur);
  }

  public static class EnvelopeBuilder {

    final List<TimeRangeExpression> insideExprs = new ArrayList<>();
    TimeRangeExpression resetExpr;

    public EnvelopeBuilder withinEach(final TimeRangeExpression expr) {
      this.resetExpr = expr;
      return this;
    }

    public EnvelopeBuilder when(final Expression<Windows> expr) {
      insideExprs.add(new TimeRangeExpression.Builder().from(expr).build());
      return this;
    }


    public EnvelopeBuilder when(final ActivityExpression expr) {
      insideExprs.add(new TimeRangeExpression.Builder().from(expr).build());
      return this;
    }

    public EnvelopeBuilder when(final TimeRangeExpression expr) {
      insideExprs.add(expr);
      return this;
    }

    public TimeWindowsTransformer build() {
      return new TransformWithReset(resetExpr, new TransformerEnvelope(insideExprs));
    }


  }


}
