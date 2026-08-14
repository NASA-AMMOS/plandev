package gov.nasa.ammos.plandev.e2e.procedural.goals;

import gov.nasa.ammos.plandev.procedural.scheduling.Goal;
import gov.nasa.ammos.plandev.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.plandev.procedural.scheduling.plan.NewDirective;
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.plandev.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Schedules each stage of the decomposition as separate activities
 */
@SchedulingProcedure
public record DecompositionSchedulingGoal() implements Goal
{
  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var blankArgs = new AnyDirective(Map.of());

    plan.create(
        new NewDirective(
            blankArgs,
            "Placed Parent Activity",
            "parent",
            new DirectiveStart.Absolute(Duration.ZERO)
        ));
    plan.create(
        new NewDirective(
            blankArgs,
            "Placed Child Activity",
            "child",
            new DirectiveStart.Absolute(Duration.MINUTE)
        ));
    plan.create(
        new NewDirective(
            blankArgs,
            "Placed Grandchild Activity",
            "grandchild",
            new DirectiveStart.Absolute(Duration.HOUR)
        ));

    plan.simulate();
    plan.commit();
    plan.simulate();
  }
}
