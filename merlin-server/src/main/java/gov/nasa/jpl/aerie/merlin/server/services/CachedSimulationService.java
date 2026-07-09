package gov.nasa.jpl.aerie.merlin.server.services;

import java.util.Optional;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsInterface;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.exceptions.SimulationDatasetMismatchException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationResultsHandle;
import gov.nasa.jpl.aerie.merlin.server.remotes.ResultsCellRepository;

import java.util.Optional;

public record CachedSimulationService (
    ResultsCellRepository store
) implements SimulationService {

  @Override
  public ResultsProtocol.State getSimulationResults(
      final PlanId planId,
      final boolean forceResim,
      final RevisionData revisionData,
      final String requestedBy)
  {
    // If force resimulation is enabled, allocate a new cell regardless of whether there was already a valid cell
    if (forceResim) {
      return this.store.forceAllocate(planId, requestedBy).get();
    }

    final var cell$ = this.store.lookup(planId);
    if (cell$.isPresent()) {
      return cell$.get().get();
    }

    // Allocate a fresh cell.
    final var cell = this.store.allocate(planId, requestedBy);
    // Return the current value of the reader; if it's incomplete, the caller can check it again later.
    return cell.get();
  }

  @Override
  public Optional<SimulationResultsHandle> get(final PlanId planId, final RevisionData revisionData) {
    return this.store.lookup(planId) // Only return results that have already been cached
        .map(ResultsProtocol.ReaderRole::get)
        .map(state -> state instanceof final ResultsProtocol.State.Success s ?
            s.results() :
            null);
  }

  @Override
  public Optional<SimulationResultsHandle> get(final PlanId planId, final SimulationDatasetId simulationDatasetId) throws SimulationDatasetMismatchException {
    return this.store.lookup(planId, simulationDatasetId) // Only return results that have already been cached
        .map(ResultsProtocol.ReaderRole::get)
        .map(state -> state instanceof final ResultsProtocol.State.Success s ?
            s.results() :
            null);
  }
}
