package gov.nasa.jpl.aerie.scheduler.server.models;

import java.util.Optional;

public record HasuraAction<I extends HasuraAction.Input>(String name, I input, Session session)
{
  public record Session(String hasuraRole, String hasuraUserId) { }

  public sealed interface Input permits SpecificationInput, MissionModelIdInput { }

  public record SpecificationInput(SpecificationId specificationId) implements Input { }
  public record MissionModelIdInput(MissionModelId missionModelId, Optional<PlanId> planId) implements  Input { }
  public record HasuraSchedulingGoalEvent(long goalId, long revision) { }
}
