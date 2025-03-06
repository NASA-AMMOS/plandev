package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;

import java.util.List;
import java.util.Map;

public interface ConstraintService {
  void createConstraintRuns(final ConstraintRequestConfiguration requestConfiguration,
                            final Map<ConstraintRecord, Fallible<ConstraintResult, ?>> constraintToResultsMap);
  Map<ConstraintRecord, DBConstraintResult> getValidConstraintRuns(List<ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId);
  void refreshConstraintProcedureParameterTypes(long constraintId, long revision);
}
