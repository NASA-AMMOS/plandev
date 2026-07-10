package gov.nasa.jpl.aerie.merlin.server.remotes;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.models.ExternalSpan;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import gov.nasa.jpl.aerie.types.Timestamp;

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
