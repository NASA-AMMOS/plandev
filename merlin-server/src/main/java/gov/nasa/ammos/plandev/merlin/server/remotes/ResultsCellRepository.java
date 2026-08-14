package gov.nasa.ammos.plandev.merlin.server.remotes;

import gov.nasa.ammos.plandev.merlin.server.ResultsProtocol;
import gov.nasa.ammos.plandev.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;
import gov.nasa.ammos.plandev.merlin.server.models.SimulationDatasetId;

import java.util.Optional;

public interface ResultsCellRepository {
  ResultsProtocol.OwnerRole allocate(PlanId planId, String requestedBy);
  ResultsProtocol.OwnerRole forceAllocate(PlanId planId, String requestedBy);

  Optional<ResultsProtocol.OwnerRole> claim(PlanId planId, Long datasetId);

  Optional<ResultsProtocol.ReaderRole> lookup(PlanId planId);

  Optional<ResultsProtocol.ReaderRole> lookup(PlanId planId, SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException;
}
