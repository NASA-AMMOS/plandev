package gov.nasa.ammos.plandev.scheduler.server.services;

import gov.nasa.ammos.plandev.procedural.scheduling.ProcedureMapper;
import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.scheduler.ProcedureLoader;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSchedulingGoalException;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.SpecificationLoadException;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.scheduler.server.http.BulkEffectiveArgumentResponse;
import gov.nasa.ammos.plandev.scheduler.server.http.ProcedureArguments;
import gov.nasa.ammos.plandev.scheduler.server.models.GoalType;
import gov.nasa.ammos.plandev.scheduler.server.models.Specification;
import gov.nasa.ammos.plandev.scheduler.server.models.SpecificationId;
import gov.nasa.ammos.plandev.scheduler.server.remotes.SpecificationRepository;
import gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.SpecificationRevisionData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

  public List<BulkEffectiveArgumentResponse> getSchedulingProcedureEffectiveArguments(
      List<ProcedureArguments> procedureArgumentsList)
  {
    final var responses = new ArrayList<BulkEffectiveArgumentResponse>();
    for (final var procedureArguments : procedureArgumentsList) {
      final GoalType goal;
      try {
        goal = specificationRepository.getGoal(procedureArguments.goalId());
        switch (goal) {
          case GoalType.EDSL edsl -> responses.add(new BulkEffectiveArgumentResponse.TypeFailure(
              procedureArguments.goalId()));
          case GoalType.JAR jar -> responses.add(new BulkEffectiveArgumentResponse.Success(
              procedureArguments.goalId(),
              ProcedureLoader
                  .loadProcedure(Path.of("/usr/src/app/merlin_file_store", jar.path().toString()))
                  .getInputType()
                  .getEffectiveArguments(procedureArguments.arguments())));
        }
      } catch (NoSuchSchedulingGoalException e) {
        responses.add(new BulkEffectiveArgumentResponse.NoGoalFailure(procedureArguments.goalId(), e));
      }
      catch (InstantiationException e) {
        responses.add(new BulkEffectiveArgumentResponse.InstantiationFailure(procedureArguments.goalId(), e));
      } catch (ProcedureLoader.ProcedureLoadException e) {
        responses.add(new BulkEffectiveArgumentResponse.ProcedureLoadFailure(procedureArguments.goalId(), e));
      }
    }
    return responses;
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
