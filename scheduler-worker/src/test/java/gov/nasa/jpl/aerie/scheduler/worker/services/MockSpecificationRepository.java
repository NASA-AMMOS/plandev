package gov.nasa.jpl.aerie.scheduler.worker.services;

import java.util.Map;
import java.util.Optional;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalId;
import gov.nasa.jpl.aerie.scheduler.server.models.GoalRecord;
import gov.nasa.jpl.aerie.scheduler.server.models.Specification;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.remotes.SpecificationRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.SpecificationRevisionData;

class MockSpecificationRepository implements SpecificationRepository
{
  Map<SpecificationId, Specification> specifications;

  MockSpecificationRepository(final Map<SpecificationId, Specification> specifications) {
    this.specifications = specifications;
  }

  @Override
  public Specification getSpecification(final SpecificationId specificationId)
  throws NoSuchSpecificationException
  {
    return Optional.ofNullable(specifications.get(specificationId))
                   .orElseThrow(() -> new NoSuchSpecificationException(specificationId));
  }

  @Override
  public SpecificationRevisionData getSpecificationRevisionData(final SpecificationId specificationId)
  throws NoSuchSpecificationException
  {
    if(!specifications.containsKey(specificationId)) throw new NoSuchSpecificationException(specificationId);
    final var spec = specifications.get(specificationId);
    return new SpecificationRevisionData(spec.specificationRevision(), spec.planRevision());
  }

  @Override
  public GoalRecord getGoal(final GoalId goalId) {
    return null;
  }

  @Override
  public void updateGoalParameterSchema(final GoalId goalId, final ValueSchema schema) {

  }
}
