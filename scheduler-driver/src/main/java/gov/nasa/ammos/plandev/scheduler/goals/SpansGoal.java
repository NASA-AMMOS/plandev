package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.RelativeActivityTemplate;

import java.util.Map;

/**
 * Describes the desired relation between activities and a set of Spans computed from simulation results.
 *
 * This definition doesn't currently allow for recursive goal definitions (i.e. true recurrence goal).
 */
public class SpansGoal {

  /*
  IMPLEMENTATION NOTES:
  1. first evaluate initialSpansExpression from simulation results
  2. FOR EACH span in initialSpansExpression, set a spans alias in (Constraints package) EvaluationEnvironment.spansInstances
     using spanAlias and evaluate relativeActivity.relativeTo().
  3. FOR EACH span in relativeActivity.relativeTo(), either find an existing activity that matches the directive or try to create
     a conflict, while trying to respect relativeActivity.allowReuse.
   */

  private Expression<Spans> initialSpansExpression;
  private String spanAlias;
  private RelativeActivityTemplate relativeActivity;
  private Boolean allowResuse;

  public static class Builder {
    private Expression<Spans> spansExpression;
    private String spanAlias;
    private RelativeActivityTemplate relativeActivity;
    private boolean allowReuse = false;

    public Builder spansExpression(Expression<Spans> expression) {
      spansExpression = expression;
      return this;
    }

    public Builder spanAlias(String alias) {
      spanAlias = alias;
      return this;
    }

    public Builder relativeActivity(RelativeActivityTemplate activity) {
      relativeActivity = activity;
      return this;
    }

    public Builder allowReuse(final boolean allowReuse) {
      this.allowReuse = allowReuse;
      return this;
    }

    public SpansGoal build() {
      if (spansExpression == null) {
        throw new IllegalArgumentException(
            "Creating spans goal requires non-null \"spansExpression\"");
      }
      if (spanAlias == null) {
        throw new IllegalArgumentException(
            "Creating spans goal requires non-null \"spanAlias\"");
      }
      if (relativeActivity == null) {
        throw new IllegalArgumentException(
            "Creating spans goal requires non-null \"relativeActivity\" directive");
      }
      final var goal = new SpansGoal();
      goal.initialSpansExpression = spansExpression;
      goal.spanAlias = spanAlias;
      goal.relativeActivity = relativeActivity;
      goal.allowResuse = allowReuse;
      return goal;
    }
  }

  private SpansGoal() { }
}
