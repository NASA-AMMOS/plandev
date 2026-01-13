package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintId;
import gov.nasa.jpl.aerie.merlin.server.models.ProcedureLoader;

import java.util.Map;

public sealed interface BulkConstraintEffectiveArgumentResponse {
  record Success(ConstraintId constraintId, Map<String, SerializedValue> effectiveArguments) implements  BulkConstraintEffectiveArgumentResponse { }
  record NoConstraintFailure(ConstraintId constraintId) implements  BulkConstraintEffectiveArgumentResponse { }
  record InstantiationFailure(ConstraintId constraintId, InstantiationException ex) implements  BulkConstraintEffectiveArgumentResponse { }
  record TypeFailure(ConstraintId constraintId) implements  BulkConstraintEffectiveArgumentResponse { }
  record ProcedureLoadFailure(ConstraintId constraintId, ProcedureLoader.ProcedureLoadException ex) implements  BulkConstraintEffectiveArgumentResponse { }
}
