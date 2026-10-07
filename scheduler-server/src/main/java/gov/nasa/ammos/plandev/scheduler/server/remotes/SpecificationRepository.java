package gov.nasa.ammos.plandev.scheduler.server.remotes;

import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSchedulingGoalException;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.ammos.plandev.scheduler.server.exceptions.SpecificationLoadException;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;
import gov.nasa.ammos.plandev.scheduler.server.models.GoalType;
import gov.nasa.ammos.plandev.scheduler.server.models.Specification;
import gov.nasa.ammos.plandev.scheduler.server.models.SpecificationId;
import gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.PlanReadOnlyCheckResult;
import gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.SpecificationRevisionData;

import java.sql.SQLException;

public interface SpecificationRepository {
  // Queries
  Specification getSpecification(SpecificationId specificationId)
  throws NoSuchSpecificationException, SpecificationLoadException;
  SpecificationRevisionData getSpecificationRevisionData(SpecificationId specificationId) throws NoSuchSpecificationException;
  GoalType getGoal(GoalId goalId) throws NoSuchSchedulingGoalException;
  PlanReadOnlyCheckResult checkPlanReadOnlyModelExecutable(SpecificationId specificationId) throws SQLException;
  void updateGoalParameterSchema(GoalId goalId, ValueSchema schema);
}
