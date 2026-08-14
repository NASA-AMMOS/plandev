package gov.nasa.jpl.aerie.scheduler.solver;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConflictSolverResult {
  private ConflictSatisfaction satisfaction;
  private Set<SchedulingActivity> activitiesCreated;

  public ConflictSolverResult(){
    this.satisfaction = ConflictSatisfaction.NOT_SAT;
    this.activitiesCreated = new HashSet<>();
  }

  public ConflictSolverResult(
      final ConflictSatisfaction conflictSatisfaction,
      final List<SchedulingActivity> activitiesSatisfying){
    this.satisfaction = conflictSatisfaction;
    this.activitiesCreated = new HashSet<>(activitiesSatisfying);
  }

  public void setSatisfaction(final ConflictSatisfaction satisfaction){
    this.satisfaction = satisfaction;
  }

  public ConflictSatisfaction satisfaction(){
    return this.satisfaction;
  }

  public Set<SchedulingActivity> activitiesCreated(){
    return this.activitiesCreated;
  }
}

