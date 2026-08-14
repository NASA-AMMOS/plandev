package gov.nasa.ammos.plandev.merlin.server.services;

import gov.nasa.ammos.plandev.procedural.constraints.ProcedureMapper;
import gov.nasa.ammos.plandev.constraints.model.ConstraintResult;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchConstraintException;
import gov.nasa.ammos.plandev.merlin.server.http.Fallible;
import gov.nasa.ammos.plandev.merlin.server.models.ConstraintId;
import gov.nasa.ammos.plandev.merlin.server.models.ConstraintRecord;
import gov.nasa.ammos.plandev.merlin.server.models.ConstraintType;
import gov.nasa.ammos.plandev.merlin.server.models.DBConstraintResult;
import gov.nasa.ammos.plandev.merlin.server.models.ProcedureLoader;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.ammos.plandev.merlin.server.remotes.ConstraintRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class LocalConstraintService implements ConstraintService {
  private final ConstraintRepository constraintRepository;

  public LocalConstraintService(
    final ConstraintRepository constraintRepository
  ) {
    this.constraintRepository = constraintRepository;
  }

  @Override
  public int createConstraintRuns(
      final ConstraintRequestConfiguration requestConfiguration,
      final Map<ConstraintRecord, Fallible<ConstraintResult, List<? extends Exception>>> constraintToResultsMap
  ) {
    return this.constraintRepository.insertConstraintRuns(requestConfiguration, constraintToResultsMap);
  }

  @Override
  public Map<ConstraintRecord, DBConstraintResult> getValidConstraintRuns(List<ConstraintRecord> constraints, SimulationDatasetId simulationDatasetId) {
    return constraintRepository.getValidConstraintRuns(constraints, simulationDatasetId);
  }

  @Override
  public void refreshConstraintProcedureParameterTypes(final long constraintId, final long revision)
  throws NoSuchConstraintException, ProcedureLoader.ProcedureLoadException
  {
    final ConstraintType constraintType;
      constraintType = constraintRepository.getConstraintType(constraintId, revision);
    switch (constraintType) {
      case ConstraintType.EDSL edsl -> { /* do nothing */ }
      case ConstraintType.JAR jar -> {
        final ProcedureMapper<?> mapper;
          mapper = ProcedureLoader.loadProcedure(Path.of("/usr/src/app/merlin_file_store", jar.path().toString()));
        final var schema = mapper.valueSchema();
        constraintRepository.updateConstraintParameterSchema(constraintId, revision, schema);
      }
    }
  }

  @Override
  public Map<ConstraintId, ConstraintRecord> getConstraintsById(final List<ConstraintId> constraintIds) {
    return constraintRepository.getConstraints(constraintIds);
  }
}
