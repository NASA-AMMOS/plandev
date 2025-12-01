package gov.nasa.jpl.plandev.merlin.server.services;

import gov.nasa.jpl.plandev.merlin.server.ResultsProtocol;
import gov.nasa.jpl.plandev.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.plandev.merlin.server.models.PlanId;
import gov.nasa.jpl.plandev.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.plandev.merlin.server.models.SimulationResultsHandle;

import java.util.Optional;

public interface SimulationService {
  ResultsProtocol.State getSimulationResults(PlanId planId, final boolean forceResim, RevisionData revisionData, final String requestedBy);

  Optional<SimulationResultsHandle> get(PlanId planId, RevisionData revisionData);

  Optional<SimulationResultsHandle> get(PlanId planId, SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException;
}
