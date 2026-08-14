package gov.nasa.ammos.plandev.scheduler.server.http;

import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.scheduler.ProcedureLoader;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSchedulingGoalException;

import java.util.Map;

public sealed interface BulkEffectiveArgumentResponse {
  GoalId goalId();
  record Success(GoalId goalId, Map<String, SerializedValue> effectiveArguments) implements  BulkEffectiveArgumentResponse { }
  record NoGoalFailure(GoalId goalId, NoSuchSchedulingGoalException ex) implements  BulkEffectiveArgumentResponse { }
  record InstantiationFailure(GoalId goalId, InstantiationException ex) implements  BulkEffectiveArgumentResponse { }
  record TypeFailure(GoalId goalId) implements  BulkEffectiveArgumentResponse { }
  record ProcedureLoadFailure(GoalId goalId, ProcedureLoader.ProcedureLoadException ex) implements  BulkEffectiveArgumentResponse { }
}
