package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.ConstraintRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ConstraintRunRecord;

import java.util.Map;

public class LocalConstraintService implements ConstraintService {
  private final ConstraintRepository constraintRepository;

  public LocalConstraintService(
    final ConstraintRepository constraintRepository
  ) {
    this.constraintRepository = constraintRepository;
  }

  @Override
  public void createConstraintRuns(final Map<Long, ConstraintRecord> constraintMap, final Map<Long, ConstraintResult> constraintResults, final SimulationDatasetId simulationDatasetId) {
    this.constraintRepository.insertConstraintRuns(constraintMap, constraintResults, simulationDatasetId.id());
  }

  @Override
  public Map<Long, ConstraintRunRecord> getValidConstraintRuns(Map<Long, ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId) {
    return constraintRepository.getValidConstraintRuns(constraints, simulationDatasetId);
  }
}
