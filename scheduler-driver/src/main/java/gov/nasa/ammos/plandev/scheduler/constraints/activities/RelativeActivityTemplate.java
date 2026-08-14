package gov.nasa.jpl.aerie.scheduler.constraints.activities;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.IntervalRelation;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;

import java.util.Map;

/**
 * Defines an activity that should be scheduled relative to a span. Used by SpansGoal.
 *
 * @param type activity type to schedule
 * @param parameterProfiles the parameters of the activity should be equal to the value of these profiles at the start of the activity
 * @param relativeTo one activity should be placed relative to each span in this expression
 * @param relation Allen Interval Algebra relation for how the activity should be placed relative to the span
 * @param allowReuse if a single existing activity or activity conflict satisfies the relations for multiple spans, whether
 *                   it can be used to satisfy all of them, or just one.
 */
public record RelativeActivityTemplate(
    ActivityType type,
    Map<String, DiscreteProfile> parameterProfiles,
    Expression<Spans> relativeTo,
    IntervalRelation relation
) {}
