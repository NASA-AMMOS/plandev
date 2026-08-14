package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationResultsHandle;

import java.util.Optional;

public interface SimulationService {
  ResultsProtocol.State getSimulationResults(PlanId planId, final boolean forceResim, RevisionData revisionData, final String requestedBy);

  Optional<SimulationResultsHandle> get(PlanId planId, RevisionData revisionData);

  Optional<SimulationResultsHandle> get(PlanId planId, SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException;
}
