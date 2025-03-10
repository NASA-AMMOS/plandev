package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ConstraintRunRecord;

import java.util.Map;

public interface ConstraintService {
  void createConstraintRuns(final ConstraintRequestConfiguration requestConfiguration,
                            final Map<ConstraintRecord, Fallible<ConstraintResult, ?>> constraintToResultsMap);
  Map<Long, ConstraintRunRecord> getValidConstraintRuns(Map<Long, ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId);
  void refreshConstraintProcedureParameterTypes(long constraintId, long revision);
}
