package gov.nasa.jpl.plandev.merlin.server.services;

import gov.nasa.jpl.plandev.constraints.model.ConstraintResult;
import gov.nasa.jpl.plandev.merlin.server.http.Fallible;
import gov.nasa.jpl.plandev.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.plandev.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.plandev.merlin.server.models.SimulationDatasetId;

import java.util.List;
import java.util.Map;

public interface ConstraintService {
  int createConstraintRuns(final ConstraintRequestConfiguration requestConfiguration,
                            final Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap);
  Map<ConstraintRecord, DBConstraintResult> getValidConstraintRuns(List<ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId);
  void refreshConstraintProcedureParameterTypes(long constraintId, long revision);
}
