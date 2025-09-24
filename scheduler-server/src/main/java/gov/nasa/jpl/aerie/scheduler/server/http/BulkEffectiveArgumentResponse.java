package gov.nasa.jpl.aerie.scheduler.server.http;

import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSchedulingGoalException;

import java.util.Map;

public sealed interface BulkEffectiveArgumentResponse {
  record Success(GoalId goalId, Map<String, SerializedValue> effectiveArguments) implements  BulkEffectiveArgumentResponse { }
  record NoGoalFailure(GoalId goalId, NoSuchSchedulingGoalException ex) implements  BulkEffectiveArgumentResponse { }
  record InstantiationFailure(GoalId goalId, InstantiationException ex) implements  BulkEffectiveArgumentResponse { }
  record TypeFailure(GoalId goalId) implements  BulkEffectiveArgumentResponse { }
  record ProcedureLoadFailure(GoalId goalId, ProcedureLoader.ProcedureLoadException ex) implements  BulkEffectiveArgumentResponse { }
}
