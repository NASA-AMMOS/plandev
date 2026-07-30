package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.InvalidSimulationDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintRecord;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
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
  long uploadSimulationDataset(
      PlanId planId,
      SimulationResults simulationResults,
      String requestedBy) throws NoSuchPlanException, InvalidSimulationDatasetException;
  SimulationResults downloadSimulationDataset(
      PlanId planId,
      long simulationDatasetId) throws NoSuchPlanException;
  void extendExternalDataset(DatasetId datasetId, ProfileSet profileSet) throws NoSuchPlanDatasetException;
  List<Pair<Duration, ProfileSet>> getExternalDatasets(
      final PlanId planId,
      final SimulationDatasetId simulationDatasetId) throws NoSuchPlanException;
  Map<String, List<ExternalEvent>> getExternalEvents(
      final PlanId planId,
      final Instant horizonStart) throws NoSuchPlanException;
  Map<String, ValueSchema> getExternalResourceSchemas(final PlanId planId, final Optional<SimulationDatasetId> simulationDatasetId) throws NoSuchPlanException;
}
