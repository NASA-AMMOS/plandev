package gov.nasa.jpl.aerie.scheduler.conflicts;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.solver.stn.TaskNetworkAdapter;

import java.util.List;
import java.util.Map;

public class MissingActivityNetworkConflict extends Conflict{
  @Override
  public Windows getTemporalContext() {
    return temporalContext;
  }

  public record ActivityDef(ActivityExpression activityExpression){}
  public TaskNetworkAdapter temporalConstraints;
  public Map<String, ActivityDef> arguments;
  public List<String> schedulingOrder;
  public Windows temporalContext;

  public MissingActivityNetworkConflict(
      final Goal goal,
      final EvaluationEnvironment evaluationEnvironment,
      final TaskNetworkAdapter stn,
      final Map<String, ActivityDef> arguments,
      final List<String> schedulingOrder,
      final Windows temporalContext) {
    super(goal, evaluationEnvironment);
    this.temporalConstraints = stn;
    this.arguments = arguments;
    this.schedulingOrder = schedulingOrder;
    this.temporalContext = temporalContext;
  }
}
