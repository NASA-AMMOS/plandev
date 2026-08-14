package gov.nasa.ammos.plandev.scheduler.server.remotes;

import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSchedulingGoalException;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.SpecificationLoadException;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.scheduler.server.models.GoalType;
import gov.nasa.ammos.plandev.scheduler.server.models.Specification;
import gov.nasa.ammos.plandev.scheduler.server.models.SpecificationId;
import gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.SpecificationRevisionData;

public interface SpecificationRepository {
  // Queries
  Specification getSpecification(SpecificationId specificationId)
  throws NoSuchSpecificationException, SpecificationLoadException;
  SpecificationRevisionData getSpecificationRevisionData(SpecificationId specificationId) throws NoSuchSpecificationException;
  GoalType getGoal(GoalId goalId) throws NoSuchSchedulingGoalException;
  void updateGoalParameterSchema(GoalId goalId, ValueSchema schema);
}
