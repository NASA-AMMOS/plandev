package gov.nasa.jpl.aerie.scheduler.goals;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.scheduler.conflicts.Conflict;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.solver.optimizers.Optimizer;
import org.apache.commons.lang3.NotImplementedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class OptionGoal extends Goal {


  private List<Goal> goals;
  private Optimizer optimizer;

  public List<Goal> getSubgoals() {
    return goals;
  }

  public boolean hasOptimizer(){
    return optimizer != null;
  }

  public Optimizer getOptimizer(){
    return optimizer;
  }

  @Override
  public java.util.Collection<Conflict> getConflicts(
      final Plan plan,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final SchedulerModel schedulerModel) {
    throw new NotImplementedException("Conflict detection is performed at solver level");
  }

  @Override
  public void extractResources(final Set<String> names) {
    super.extractResources(names);
    for(final var goal: goals){
      goal.extractResources(names);
    }
  }

  public static class Builder extends Goal.Builder<OptionGoal.Builder> {

    final List<Goal> goals = new ArrayList<>();

    Optimizer optimizer;

    public Builder or(Goal goal) {
      goals.add(goal);
      return getThis();
    }

    public Builder optimizingFor(Optimizer s) {
      optimizer = s;
      return getThis();
    }

    @Override
    protected Builder getThis() {
      return this;
    }

    public OptionGoal build() {
      OptionGoal dg = new OptionGoal();
      super.fill(dg);
      dg.goals = goals;
      dg.name = name;
      dg.optimizer = optimizer;
      return dg;
    }
  }
}
