package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;
import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchConstraintException;
import gov.nasa.jpl.aerie.merlin.server.http.Fallible;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintType;
import gov.nasa.jpl.aerie.merlin.server.models.DBConstraintResult;
import gov.nasa.jpl.aerie.merlin.server.models.ProcedureLoader;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.ConstraintRepository;

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
  public void refreshConstraintProcedureParameterTypes(final long constraintId, final long revision) {
    final ConstraintType constraintType;
    try {
      constraintType = constraintRepository.getConstraintType(constraintId, revision);
    } catch (NoSuchConstraintException e) {
      throw new RuntimeException(e);
    }
    switch (constraintType) {
      case ConstraintType.EDSL edsl -> { /* do nothing */ }
      case ConstraintType.JAR jar -> {
        final ProcedureMapper<?> mapper;
        try {
          mapper = ProcedureLoader.loadProcedure(Path.of("/usr/src/app/merlin_file_store", jar.path().toString()));
        } catch (ProcedureLoader.ProcedureLoadException e) {
          throw new RuntimeException(e);
        }
        final var schema = mapper.valueSchema();
        constraintRepository.updateConstraintParameterSchema(constraintId, revision, schema);
      }
    }
  }
}
