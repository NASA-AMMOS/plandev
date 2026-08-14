package gov.nasa.ammos.plandev.merlin.server.remotes;

import gov.nasa.ammos.plandev.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanDatasetException;
import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.ammos.plandev.merlin.server.models.DatasetId;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.ProfileSet;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.ammos.plandev.merlin.server.models.ConstraintRecord;
import gov.nasa.ammos.plandev.merlin.server.services.RevisionData;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import gov.nasa.ammos.plandev.types.Plan;
import gov.nasa.ammos.plandev.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An owned interface to a concurrency-safe store of plans.
 *
 * A {@code PlanRepository} provides access to a shared store of plans, each indexed by a unique ID.
 * To support concurrent access, updates to the store must be concurrency-controlled. Every concurrent agent must have its
 * own {@code PlanRepository} reference, so that the reads and writes of each agent may be tracked analogously to
 * <a href="https://en.wikipedia.org/wiki/Load-link/store-conditional">load-link/store-conditional</a> semantics.
 */
public interface PlanRepository {
  // Queries
  Map<PlanId, Plan> getAllPlans();
  Plan getPlanForValidation(PlanId planId) throws NoSuchPlanException;
  Plan getPlanForSimulation(PlanId planId) throws NoSuchPlanException;
  long getPlanRevision(PlanId planId) throws NoSuchPlanException;
  RevisionData getPlanRevisionData(PlanId planId) throws NoSuchPlanException;

  List<ConstraintRecord> getPlanConstraints(PlanId planId) throws NoSuchPlanException;

  long addExternalDataset(
      PlanId planId,
      Optional<SimulationDatasetId> simulationDatasetId,
      Timestamp datasetStart,
      ProfileSet profileSet) throws NoSuchPlanException;
  void extendExternalDataset(DatasetId datasetId, ProfileSet profileSet) throws NoSuchPlanDatasetException;
  List<Pair<Duration, ProfileSet>> getExternalDatasets(
      PlanId planId,
      SimulationDatasetId simulationDatasetId) throws NoSuchPlanException;
  Map<String, List<ExternalEvent>> getExternalEvents(
      final PlanId planId,
      final Instant horizonStart) throws NoSuchPlanException;
  Map<String, ValueSchema> getExternalResourceSchemas(PlanId planId, Optional<SimulationDatasetId> simulationDatasetId) throws NoSuchPlanException;

  record CreatedPlan(PlanId planId, List<ActivityDirectiveId> activityIds) {}
}
