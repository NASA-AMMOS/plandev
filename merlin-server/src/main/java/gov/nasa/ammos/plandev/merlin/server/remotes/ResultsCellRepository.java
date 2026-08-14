package gov.nasa.jpl.aerie.merlin.server.remotes;

import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;

import java.util.Optional;

public interface ResultsCellRepository {
  ResultsProtocol.OwnerRole allocate(PlanId planId, String requestedBy);
  ResultsProtocol.OwnerRole forceAllocate(PlanId planId, String requestedBy);

  Optional<ResultsProtocol.OwnerRole> claim(PlanId planId, Long datasetId);

  Optional<ResultsProtocol.ReaderRole> lookup(PlanId planId);

  Optional<ResultsProtocol.ReaderRole> lookup(PlanId planId, SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException;
}
