package gov.nasa.jpl.aerie.merlin.server.remotes;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ConstraintRunRecord;

import java.util.Map;

public interface ConstraintRepository {
  void insertConstraintRuns(final Map<Long, ConstraintRecord> constraintMap, final Map<Long, ConstraintResult> constraintResults,
                            final Long simulationDatasetId);

  Map<Long, ConstraintRunRecord> getValidConstraintRuns(Map<Long, ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId);
}
