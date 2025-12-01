package gov.nasa.jpl.plandev.merlin.server.remotes;

import gov.nasa.jpl.plandev.constraints.model.ConstraintResult;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.plandev.merlin.server.exceptions.NoSuchConstraintException;
import gov.nasa.jpl.plandev.merlin.server.http.Fallible;
import gov.nasa.jpl.plandev.merlin.server.models.ConstraintType;
import gov.nasa.jpl.plandev.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.plandev.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.plandev.merlin.server.services.ConstraintRequestConfiguration;

import java.util.List;
import java.util.Map;

public interface ConstraintRepository {
  int insertConstraintRuns(final ConstraintRequestConfiguration requestConfiguration,
      final Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap);

  Map<ConstraintRecord, DBConstraintResult> getValidConstraintRuns(List<ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId);
  ConstraintType getConstraintType(final long constraintId, final long revision) throws NoSuchConstraintException;
  void updateConstraintParameterSchema(final long constraintId, final long revision, final ValueSchema schema);
}
