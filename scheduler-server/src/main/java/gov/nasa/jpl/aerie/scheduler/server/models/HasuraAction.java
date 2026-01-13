package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.scheduler.server.http.ProcedureArguments;
import gov.nasa.jpl.aerie.types.MissionModelId;

import java.util.List;
import java.util.Optional;

public record HasuraAction<I extends HasuraAction.Input>(String name, I input, Session session)
{
  public record Session(String hasuraRole, String hasuraUserId) { }

  public sealed interface Input permits SpecificationInput, MissionModelIdInput, HasuraBulkEffectiveArguments{ }

  public record SpecificationInput(SpecificationId specificationId) implements Input { }
  public record MissionModelIdInput(MissionModelId missionModelId, Optional<PlanId> planId) implements  Input { }
  public record HasuraSchedulingGoalEvent(long goalId, long revision) { }
  public record HasuraBulkEffectiveArguments(List<ProcedureArguments> items) implements Input { }
}
