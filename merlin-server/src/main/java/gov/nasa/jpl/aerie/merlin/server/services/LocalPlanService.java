package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.remotes.PlanRepository;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

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
  public Map<String, ValueSchema> getExternalResourceSchemas(final PlanId planId, final Optional<SimulationDatasetId> simulationDatasetId) throws NoSuchPlanException {
    return this.planRepository.getExternalResourceSchemas(planId, simulationDatasetId);
  }
}
