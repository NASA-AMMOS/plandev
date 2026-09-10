package gov.nasa.ammos.plandev.merlin.server.remotes;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.server.models.ExternalSpan;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.ProfileSet;
import gov.nasa.ammos.plandev.types.Timestamp;

import java.util.List;
import java.util.Optional;

public interface ExternalSimulationResultsRepository {
  /**
   * Ingest foreign simulation results as a first-class SUCCESS simulation_dataset.
   * @return the created simulation_dataset id (merlin.simulation_dataset.id)
   */
  long insertExternalSimulationResults(
      PlanId planId,
      Optional<Long> simulationId,
      Timestamp simulationStart,
      Duration simulationDuration,
      ProfileSet profileSet,
      List<ExternalSpan> spans,
      String requestedBy);
}
