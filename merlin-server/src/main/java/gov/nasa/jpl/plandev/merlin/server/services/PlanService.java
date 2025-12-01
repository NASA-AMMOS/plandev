package gov.nasa.jpl.plandev.merlin.server.services;

import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.plandev.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.jpl.plandev.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.plandev.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.plandev.merlin.server.models.DatasetId;
import gov.nasa.jpl.plandev.merlin.server.models.PlanId;
import gov.nasa.jpl.plandev.merlin.server.models.ProfileSet;
import gov.nasa.jpl.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.plandev.types.Plan;
import gov.nasa.jpl.plandev.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PlanService {
  Plan getPlanForSimulation(PlanId planId) throws NoSuchPlanException;
  Plan getPlanForValidation(PlanId planId) throws NoSuchPlanException;
  RevisionData getPlanRevisionData(PlanId planId) throws NoSuchPlanException;

  List<ConstraintRecord> getConstraintsForPlan(PlanId planId) throws NoSuchPlanException;

  long addExternalDataset(
      PlanId planId,
      Optional<SimulationDatasetId> simulationDatasetId,
      Timestamp datasetStart,
      ProfileSet profileSet) throws NoSuchPlanException;
  void extendExternalDataset(DatasetId datasetId, ProfileSet profileSet) throws NoSuchPlanDatasetException;
  List<Pair<Duration, ProfileSet>> getExternalDatasets(
      final PlanId planId,
      final SimulationDatasetId simulationDatasetId) throws NoSuchPlanException;
  Map<String, ValueSchema> getExternalResourceSchemas(final PlanId planId, final Optional<SimulationDatasetId> simulationDatasetId) throws NoSuchPlanException;
}
