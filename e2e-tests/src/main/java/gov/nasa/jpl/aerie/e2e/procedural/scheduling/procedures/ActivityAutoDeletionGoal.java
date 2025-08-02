package gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.ActivityAutoDelete;
import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Creates one activity, and deletes it automatically on subsequent runs.
 */
@SchedulingProcedure
public record ActivityAutoDeletionGoal(boolean deleteAtBeginning) implements Goal {
  @NotNull
  @Override
  public ActivityAutoDelete shouldDeletePastCreations(
      @NotNull final Plan plan,
      @Nullable final SimulationResults simResults)
  {
    if (deleteAtBeginning) return new ActivityAutoDelete.AtBeginning(DeletedAnchorStrategy.Error, false);
    else return new ActivityAutoDelete.JustBefore(DeletedAnchorStrategy.Error);
  }

  @Override
  public void run(@NotNull final EditablePlan plan) {
    plan.create(
        "BiteBanana",
        new DirectiveStart.Absolute(Duration.MINUTE),
        Map.of("biteSize", SerializedValue.of(1))
    );

    plan.commit();
  }
}
