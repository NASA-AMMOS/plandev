package gov.nasa.jpl.aerie.scheduler.server.services;

import gov.nasa.ammos.aerie.procedural.scheduling.ProcedureMapper;
import gov.nasa.jpl.aerie.scheduler.ProcedureLoader;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSchedulingGoalException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.SpecificationLoadException;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalType;
import gov.nasa.jpl.aerie.scheduler.server.models.Specification;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.remotes.SpecificationRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.SpecificationRevisionData;

import java.nio.file.Path;

public record SpecificationService(SpecificationRepository specificationRepository) {
  // Queries
  public Specification getSpecification(final SpecificationId specificationId)
  throws NoSuchSpecificationException, SpecificationLoadException
  {
    return specificationRepository.getSpecification(specificationId);
  }

  public SpecificationRevisionData getSpecificationRevisionData(final SpecificationId specificationId)
  throws NoSuchSpecificationException
  {
    return specificationRepository.getSpecificationRevisionData(specificationId);
  }

  public void refreshSchedulingProcedureParameterTypes(long goalId, long revision) {
    final GoalType goal;
    try {
      goal = specificationRepository.getGoal(new GoalId(goalId, revision));
    } catch (NoSuchSchedulingGoalException e) {
      throw new RuntimeException(e);
    }
    switch (goal) {
      case GoalType.EDSL edsl -> {
        // Do nothing
      }
      case GoalType.JAR jar -> {
        final ProcedureMapper<?> mapper;
        try {
          mapper = ProcedureLoader.loadProcedure(Path.of("/usr/src/app/merlin_file_store", jar.path().toString()));
        } catch (ProcedureLoader.ProcedureLoadException e) {
          throw new RuntimeException(e);
        }
        final var schema = mapper.valueSchema();
        specificationRepository.updateGoalParameterSchema(new GoalId(goalId, revision), schema);
      }
    }
  }
}
