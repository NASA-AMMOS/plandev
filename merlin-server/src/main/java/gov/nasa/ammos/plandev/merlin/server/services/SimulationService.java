package gov.nasa.ammos.plandev.merlin.server.services;

import gov.nasa.ammos.plandev.merlin.server.ResultsProtocol;
import gov.nasa.ammos.plandev.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationResultsHandle;

import java.util.Optional;

public interface SimulationService {
  ResultsProtocol.State getSimulationResults(PlanId planId, final boolean forceResim, RevisionData revisionData, final String requestedBy);

  Optional<SimulationResultsHandle> get(PlanId planId, RevisionData revisionData);

  Optional<SimulationResultsHandle> get(PlanId planId, SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException;
}
