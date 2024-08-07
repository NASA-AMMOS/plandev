package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.ActivitySpan;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivitySpans;
import gov.nasa.jpl.aerie.constraints.tree.SpansFromWindows;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.scheduler.Range;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelativeBinary;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelativeSimple;
import gov.nasa.jpl.aerie.scheduler.goals.CardinalityGoal;
import gov.nasa.jpl.aerie.scheduler.goals.CoexistenceGoal;
import gov.nasa.jpl.aerie.scheduler.goals.CompositeAndGoal;
import gov.nasa.jpl.aerie.scheduler.goals.Goal;
import gov.nasa.jpl.aerie.scheduler.goals.OptionGoal;
import gov.nasa.jpl.aerie.scheduler.goals.Procedure;
import gov.nasa.jpl.aerie.scheduler.goals.RecurrenceGoal;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.PersistentTimeAnchor;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.server.models.SchedulingDSL;
import gov.nasa.jpl.aerie.scheduler.server.models.Timestamp;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.function.Function;

public class GoalBuilder {
  private GoalBuilder() {}
  public static Goal goalOfGoalSpecifier(
      final SchedulingDSL.GoalSpecifier goalSpecifier,
      final Timestamp horizonStartTimestamp,
      final Timestamp horizonEndTimestamp,
      final Function<String, ActivityType> lookupActivityType,
      final boolean simulateAfter) {
    final var planningHorizon = new PlanningHorizon(
        horizonStartTimestamp.toInstant(),
        horizonEndTimestamp.toInstant());
    final var hor = planningHorizon.getHor();
    switch (goalSpecifier) {
      case SchedulingDSL.GoalSpecifier.RecurrenceGoalDefinition g -> {
        final var builder = new RecurrenceGoal.Builder()
            .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor, true)))
            .repeatingEvery(g.interval())
            .shouldRollbackIfUnsatisfied(g.shouldRollbackIfUnsatisfied())
            .thereExistsOne(makeActivityTemplate(g.activityTemplate(), lookupActivityType))
            .withinPlanHorizon(planningHorizon)
            .simulateAfter(simulateAfter);
        if (g.activityFinder().isPresent()) {
          builder.match(buildActivityExpression(g.activityFinder().get(), lookupActivityType));
        }
        return builder.build();
      }

      case SchedulingDSL.GoalSpecifier.CoexistenceGoalDefinition g -> {
        var builder = new CoexistenceGoal.Builder()
            .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor, true)))
            .createPersistentAnchor(g.persistentAnchor().isPresent()? g.persistentAnchor().get() : PersistentTimeAnchor.DISABLED)
            .forEach(spansOfConstraintExpression(
                g.forEach()))
            .thereExistsOne(makeActivityTemplate(g.activityTemplate(), lookupActivityType))
            .withinPlanHorizon(planningHorizon)
            .simulateAfter(simulateAfter)
            .shouldRollbackIfUnsatisfied(g.shouldRollbackIfUnsatisfied())
            .aliasForAnchors(g.alias());
        if (g.startConstraint().isPresent()) {
          final var startConstraint = g.startConstraint().get();
          if (startConstraint instanceof SchedulingDSL.TimingConstraint.ActivityTimingConstraint s) {
            builder.startsAt(makeTimeExpressionRelativeSimple(s));
          } else if (startConstraint instanceof SchedulingDSL.TimingConstraint.ActivityTimingConstraintFlexibleRange s) {
            builder.startsAt(new TimeExpressionRelativeBinary(makeTimeExpressionRelativeSimple(s.lowerBound()), makeTimeExpressionRelativeSimple(s.upperBound())));
          } else {
            throw new UnexpectedSubtypeError(SchedulingDSL.TimingConstraint.class, startConstraint);
          }
        }
        if (g.endConstraint().isPresent()) {
          final var endConstraint = g.endConstraint().get();
          if (endConstraint instanceof SchedulingDSL.TimingConstraint.ActivityTimingConstraint e) {
            builder.endsAt(makeTimeExpressionRelativeSimple(e));
          } else if (endConstraint instanceof SchedulingDSL.TimingConstraint.ActivityTimingConstraintFlexibleRange e) {
            builder.endsAt(new TimeExpressionRelativeBinary(makeTimeExpressionRelativeSimple(e.lowerBound()), makeTimeExpressionRelativeSimple(e.upperBound())));
          } else {
            throw new UnexpectedSubtypeError(SchedulingDSL.TimingConstraint.class, endConstraint);
          }
        }
        if (g.startConstraint().isEmpty() && g.endConstraint().isEmpty()) {
          throw new Error("Both start and end constraints were empty. This should have been disallowed at the type level.");
        }
        if(g.activityFinder().isPresent()){
          builder.match(buildActivityExpression(g.activityFinder().get(), lookupActivityType));
        }
        return builder.build();
      }

      case SchedulingDSL.GoalSpecifier.GoalAnd g -> {
        var builder = new CompositeAndGoal.Builder();
        for (final var subGoalSpecifier : g.goals()) {
          builder = builder.and(goalOfGoalSpecifier(subGoalSpecifier,
                                                    horizonStartTimestamp,
                                                    horizonEndTimestamp,
                                                    lookupActivityType,
                                                    simulateAfter));
        }
        builder.simulateAfter(simulateAfter);
        builder.withinPlanHorizon(planningHorizon);
        builder.forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor, true)));
        builder.shouldRollbackIfUnsatisfied(g.shouldRollbackIfUnsatisfied());
        return builder.build();
      }

      case SchedulingDSL.GoalSpecifier.GoalOr g -> {
        var builder = new OptionGoal.Builder();
        for (final var subGoalSpecifier : g.goals()) {
          builder = builder.or(goalOfGoalSpecifier(subGoalSpecifier,
                                                   horizonStartTimestamp,
                                                   horizonEndTimestamp,
                                                   lookupActivityType,
                                                   simulateAfter));
        }
        builder.simulateAfter(simulateAfter);
        builder.withinPlanHorizon(planningHorizon);
        builder.forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor, true)));
        builder.shouldRollbackIfUnsatisfied(g.shouldRollbackIfUnsatisfied());
        return builder.build();
      }

      case SchedulingDSL.GoalSpecifier.GoalApplyWhen g -> {
        var goal = goalOfGoalSpecifier(g.goal(), horizonStartTimestamp, horizonEndTimestamp, lookupActivityType, simulateAfter);
        goal.setTemporalContext(g.windows());
        return goal;
      }

      case SchedulingDSL.GoalSpecifier.CardinalityGoalDefinition g -> {
        final var builder = new CardinalityGoal.Builder()
            .thereExistsOne(makeActivityTemplate(g.activityTemplate(), lookupActivityType))
            .simulateAfter(simulateAfter)
            .forAllTimeIn(new WindowsWrapperExpression(new Windows(false).set(hor, true)))
            .withinPlanHorizon(planningHorizon)
            .shouldRollbackIfUnsatisfied(g.shouldRollbackIfUnsatisfied());
        if (g.specification().duration().isPresent()) {
          builder.duration(Interval.between(g.specification().duration().get(), Duration.MAX_VALUE));
        }
        if (g.specification().occurrence().isPresent()) {
          builder.occurences(new Range<>(g.specification().occurrence().get(), Integer.MAX_VALUE));
        }
        if (g.activityFinder().isPresent()) {
          builder.match(buildActivityExpression(g.activityFinder().get(), lookupActivityType));
        }
        return builder.build();
      }

      case SchedulingDSL.GoalSpecifier.Procedure g -> {
        return new Procedure(planningHorizon, g.jarPath(), g.arguments(), simulateAfter);
      }
    }
  }

  @NotNull
  private static TimeExpressionRelativeSimple makeTimeExpressionRelativeSimple(final SchedulingDSL.TimingConstraint.ActivityTimingConstraint s) {
    final var timeExpression = new TimeExpressionRelativeSimple(
        s.windowProperty(),
        s.singleton()
    );
    timeExpression.addOperation(s.operator(), s.operand());
    return timeExpression;
  }

  private static ActivityExpression buildActivityExpression(SchedulingDSL.ConstraintExpression.ActivityExpression activityExpr,
                                                            final Function<String, ActivityType> lookupActivityType){
    final var builder = new ActivityExpression.Builder().ofType(lookupActivityType.apply(activityExpr.type()));
    if(activityExpr.arguments().isPresent()){
      activityExpr.arguments().get().fields().forEach(builder::withArgument);
    }
    return builder.build();
  }

  private static Expression<Spans> spansOfConstraintExpression(
      final SchedulingDSL.ConstraintExpression constraintExpression) {
    if (constraintExpression instanceof SchedulingDSL.ConstraintExpression.ActivityExpression c) {
      return new ForEachActivitySpans(
          new TriFunction<>() {
            @Override
            public Boolean apply(
                final ActivityInstance activityInstance,
                final SimulationResults simResults,
                final EvaluationEnvironment environment)
            {
              final var startTime = activityInstance.interval().start;
              if (!activityInstance.type().equals(c.type())) return false;
              for (final var arg : c
                  .arguments()
                  .map(expr -> expr.evaluateMap(simResults, startTime, environment))
                  .orElse(Map.of())
                  .entrySet()) {
                if (!arg.getValue().equals(activityInstance.parameters().get(arg.getKey()))) return false;
              }
              return true;
            }

            @Override
            public String toString() {
              return "(filter by ActivityExpression)";
            }
          },
          "alias" + c.type(),
          new ActivitySpan("alias" + c.type())
      );
    } else if (constraintExpression instanceof SchedulingDSL.ConstraintExpression.WindowsExpression c){
      return new SpansFromWindows(c.expression());
    } else {
      throw new UnexpectedSubtypeError(SchedulingDSL.ConstraintExpression.class, constraintExpression);
    }
  }

  private static ActivityExpression makeActivityTemplate(
      final SchedulingDSL.ActivityTemplate activityTemplate,
      final Function<String, ActivityType> lookupActivityType) {
    var builder = new ActivityExpression.Builder();
    final var type = lookupActivityType.apply(activityTemplate.activityType());
    if(type.getDurationType() instanceof DurationType.Controllable durationType){
      //detect duration parameter
      if(activityTemplate.arguments().fields().containsKey(durationType.parameterName())){
        final var argument = activityTemplate.arguments().fields().get(durationType.parameterName());
        if(argument != null){
          builder.durationIn(argument.expression);
          activityTemplate.arguments().fields().remove(durationType.parameterName());
        } else {
          //nothing, other cases will be handled by below section
        }
      }
    }
    builder = builder.ofType(type);
    for (final var argument : activityTemplate.arguments().fields().entrySet()) {
      builder.withArgument(argument.getKey(), argument.getValue());
    }
    return builder.build();
  }
}
