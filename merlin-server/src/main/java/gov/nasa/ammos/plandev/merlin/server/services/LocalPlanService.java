package gov.nasa.ammos.plandev.merlin.server.services;

import gov.nasa.ammos.plandev.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.ammos.plandev.merlin.server.models.ConstraintRecord;
import gov.nasa.ammos.plandev.merlin.server.models.DatasetId;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.ProfileSet;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.ammos.plandev.merlin.server.remotes.PlanRepository;
import gov.nasa.ammos.plandev.types.Plan;
import gov.nasa.ammos.plandev.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LocalPlanService implements PlanService {
  private final PlanRepository planRepository;

  public LocalPlanService(
      final PlanRepository planRepository
  ) {
    this.planRepository = planRepository;
  }

  @Override
  public Plan getPlanForSimulation(final PlanId planId) throws NoSuchPlanException {
    return this.planRepository.getPlanForSimulation(planId);
  }

  @Override
  public Plan getPlanForValidation(final PlanId planId) throws NoSuchPlanException {
    return this.planRepository.getPlanForValidation(planId);
  }

  @Override
  public RevisionData getPlanRevisionData(final PlanId planId) throws NoSuchPlanException {
    return this.planRepository.getPlanRevisionData(planId);
  }

  @Override
  public List<ConstraintRecord> getConstraintsForPlan(final PlanId planId) throws NoSuchPlanException {
    return this.planRepository.getPlanConstraints(planId);
  }

  @Override
  public long addExternalDataset(
      final PlanId planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final Timestamp datasetStart,
      final ProfileSet profileSet)
  throws NoSuchPlanException
  {
    return this.planRepository.addExternalDataset(planId, simulationDatasetId, datasetStart, profileSet);
  }

  @Override
  public void extendExternalDataset(final DatasetId datasetId, final ProfileSet profileSet)
  throws NoSuchPlanDatasetException
  {
    this.planRepository.extendExternalDataset(datasetId, profileSet);
  }

  @Override
  public List<Pair<Duration, ProfileSet>> getExternalDatasets(
      final PlanId planId,
      final SimulationDatasetId simulationDatasetId) throws NoSuchPlanException
  {
    return this.planRepository.getExternalDatasets(planId, simulationDatasetId);
  }

  @Override
  public Map<String, List<ExternalEvent>> getExternalEvents(
      final PlanId planId,
      final Instant horizonStart) throws NoSuchPlanException {
    return this.planRepository.getExternalEvents(planId, horizonStart);
  }

  @Override
  public Map<String, ValueSchema> getExternalResourceSchemas(final PlanId planId, final Optional<SimulationDatasetId> simulationDatasetId) throws NoSuchPlanException {
    return this.planRepository.getExternalResourceSchemas(planId, simulationDatasetId);
  }
}
