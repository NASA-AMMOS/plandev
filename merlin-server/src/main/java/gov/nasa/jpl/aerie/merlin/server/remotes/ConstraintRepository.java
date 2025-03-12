package gov.nasa.jpl.aerie.merlin.server.remotes;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchConstraintException;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintType;
import gov.nasa.jpl.aerie.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.services.ConstraintRequestConfiguration;

import java.util.List;
import java.util.Map;

public interface ConstraintRepository {
  void insertConstraintRuns(final ConstraintRequestConfiguration requestConfiguration,
      final Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap);

  Map<ConstraintRecord, DBConstraintResult> getValidConstraintRuns(List<ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId);
  ConstraintType getConstraintType(final long constraintId, final long revision) throws NoSuchConstraintException;
  void updateConstraintParameterSchema(final long constraintId, final long revision, final ValueSchema schema);
}
