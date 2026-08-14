package gov.nasa.jpl.aerie.scheduler.conflicts;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.goals.ActivityTemplateGoal;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.solver.ScheduleAt;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Optional;

/**
 * describes plan problem due to lack of a matching instance for a template
 * such conflicts are typically addressed by scheduling additional activities
 * using the corresponding creation template
 */
public class MissingActivityTemplateConflict extends MissingActivityConflict {

  /**
   * ctor creates a new conflict regarding a missing activity
   *
   * @param goal  the dissatisfied goal that issued the conflict
   * @param temporalContext  the times in the plan when the goal was
   * @param template  desired activity template
   * @param evaluationEnvironment the evaluation environment at the time of creation so variables can be retrieved later at instantiation
   * @param cardinality the desired number of times the activity template should be inserted
   * @param anchorIdTo  represents the id of the activity to which we need to create an anchor
   * @param totalDuration the desired total duration
   */
  public MissingActivityTemplateConflict(
      Goal goal,
      Windows temporalContext,
      ActivityExpression template,
      EvaluationEnvironment evaluationEnvironment,
      int cardinality,
      Optional<ActivityDirectiveId> anchorIdTo,
      Optional<Boolean> anchorToStart,
      Optional<Duration> totalDuration,
      ScheduleAt scheduleAtEarliest)
  {
    super(goal, evaluationEnvironment);

    if (temporalContext == null) {
      throw new IllegalArgumentException(
          "creating missing activity template conflict requires non-null temporal context");
    }
    this.temporalContext = temporalContext;
    this.template = template;
    this.cardinality = cardinality;
    this.anchorIdTo = anchorIdTo;
    this.anchorToStart = anchorToStart;
    this.totalDuration = totalDuration;
    this.scheduleAt = scheduleAtEarliest;
  }

  //the number of times the activity needs to be inserted
  int cardinality;
  Optional<ActivityDirectiveId> anchorIdTo;
  Optional<Boolean> anchorToStart;

  public Optional<ActivityDirectiveId> getAnchorId(){
    return anchorIdTo;
  }

  public Optional<Boolean> getAnchorToStart() {
    return anchorToStart;
  }
  //the desired total duration over the number of activities needed
  Optional<Duration> totalDuration;

  ScheduleAt scheduleAt;

  public int getCardinality(){
    return cardinality;
  }

  public Optional<Duration> getTotalDuration(){
    return totalDuration;
  }

  @Override
  public ScheduleAt scheduleAt() {
    return scheduleAt;
  }

  /**
   * {@inheritDoc}
   *
   * the times over which the missing activity template is causing a problem
   *
   * does not consider other constraints on the possible activity, eg timing
   * with respect to other events or allowable state transitions or even
   * the activity's own duration limits
   *
   * the times encompass just the desired start times of the missing activity
   * @return
   */
  @Override
  public Windows getTemporalContext() {
    return temporalContext;
  }

  /**
   * the goal whose dissatisfaction initially created this conflict
   *
   * @return reference to the dissatisfied goal that caused this conflict
   */
  @Override
  public ActivityTemplateGoal getGoal() {
    return (ActivityTemplateGoal) super.getGoal();
  }

  /**
   * the times over which the activity templates' absence causes a problem
   *
   * see more details at accessor getTemporalContext()
   */
  private final Windows temporalContext;
  /**
   * The conflict can constraint the goal template to guide the search
   */
  private final ActivityExpression template;

  public ActivityExpression getActTemplate() {
    return template;
  }

  @Override
  public String toString(){
    return "Conflict: missing activity template [cardinality:"
           + this.getCardinality()
           + ", duration: "
           + (this.getTotalDuration().isPresent() ? this.getTotalDuration() : "N/A")
           +"] of type: "
           + getActTemplate().type().getName()
           + " in temporal context "
           + getTemporalContext().trueSegmentsToString()
           + ". Produced by goal " + getGoal().getName();
  }
}
