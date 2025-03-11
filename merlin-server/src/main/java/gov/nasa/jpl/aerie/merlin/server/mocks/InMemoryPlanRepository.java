package gov.nasa.jpl.aerie.merlin.server.mocks;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.PlanRepository;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class InMemoryPlanRepository implements PlanRepository {
  private final Map<PlanId, Pair<Long, Plan>> plans = new HashMap<>();
  private int nextPlanId = 0;
  private int nextActivityId = 0;

  @Override
  public Map<PlanId, Plan> getAllPlans() {
    return this.plans
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            entry -> entry.getKey(),
            entry -> new Plan(entry.getValue().getRight())));
  }

  @Override
  public Plan getPlanForValidation(final PlanId planId) throws NoSuchPlanException {
    final Plan plan = Optional
        .ofNullable(this.plans.get(planId))
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .getRight();

    return new Plan(plan);
  }

  @Override
  public Plan getPlanForSimulation(final PlanId planId) throws NoSuchPlanException {
    final Plan plan = Optional
        .ofNullable(this.plans.get(planId))
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .getRight();

    return new Plan(plan);
  }

  @Override
  public long getPlanRevision(final PlanId planId) throws NoSuchPlanException {
    return Optional
        .ofNullable(this.plans.get(planId))
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .getLeft();
  }

  @Override
  public InMemoryRevisionData getPlanRevisionData(final PlanId planId) throws NoSuchPlanException {
    return new InMemoryRevisionData(
        Optional
            .ofNullable(this.plans.get(planId))
            .orElseThrow(() -> new NoSuchPlanException(planId))
            .getLeft());
  }

  public CreatedPlan storePlan(final Plan other) {
    final PlanId planId = new PlanId(this.nextPlanId++);
    final Plan plan = new Plan(other);
    final List<ActivityDirectiveId> activityIds = List.copyOf(plan.activityDirectives().keySet());

    this.plans.put(planId, Pair.of(0L, plan));
    return new CreatedPlan(planId, activityIds);
  }

  public void deletePlan(final PlanId planId) throws NoSuchPlanException {
    if (!this.plans.containsKey(planId)) {
      throw new NoSuchPlanException(planId);
    }

    this.deleteAllActivities(planId);
    this.plans.remove(planId);
  }

  public ActivityDirectiveId createActivity(final PlanId planId, final ActivityDirective activity) throws NoSuchPlanException {
    final var entry = this.plans.get(planId);
    if (entry == null) throw new NoSuchPlanException(planId);

    final var plan = entry.getRight();
    final var revision = entry.getLeft() + 1;

    final ActivityDirectiveId activityId = new ActivityDirectiveId(this.nextActivityId++);
    plan.activityDirectives().put(activityId, activity);
    this.plans.put(planId, Pair.of(revision, plan));

    return activityId;
  }

  public void deleteAllActivities(final PlanId planId) throws NoSuchPlanException {
    final var entry = this.plans.get(planId);
    if (entry == null) throw new NoSuchPlanException(planId);

    final var plan = entry.getRight();
    final var revision = entry.getLeft() + 1;

    plan.activityDirectives().clear();
    this.plans.put(planId, Pair.of(revision, plan));
  }

  @Override
  public Map<Long, ConstraintRecord> getPlanConstraints(final PlanId planId) {
    return Map.of();
  }

  @Override
  public long addExternalDataset(
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final Timestamp datasetStart,
      final ProfileSet profileSet)
  {
    return 0;
  }

  @Override
  public void extendExternalDataset(final DatasetId datasetId, final ProfileSet profileSet) {
    throw new UnsupportedOperationException("InMemoryPlanRepository does not store external datasets, so they cannot be extended");
  }

  @Override
  public List<Pair<Duration, ProfileSet>> getExternalDatasets(
      final PlanId planId,
      final SimulationDatasetId simulationDatasetId)
  {
    return List.of();
  }

  @Override
  public Map<String, ValueSchema> getExternalResourceSchemas(final PlanId planId, final Optional<SimulationDatasetId> simulationDatasetId) {
    return Map.of();
  }
}
