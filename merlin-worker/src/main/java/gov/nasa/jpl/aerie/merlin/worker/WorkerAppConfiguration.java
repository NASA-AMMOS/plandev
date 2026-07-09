package gov.nasa.jpl.aerie.merlin.worker;

import gov.nasa.jpl.aerie.merlin.server.config.Store;
import gov.nasa.jpl.aerie.merlin.server.services.SimulationReuseStrategy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * options controlling the merlin worker connections/behavior
 *
 * @param simReuseStrategy how to reuse prior simulations to speed up the current simulation request
 */
public record WorkerAppConfiguration(
    Path merlinFileStore,
    Store store,
    long simulationProgressPollPeriodMillis,
    Instant untruePlanStart,
    SimulationReuseStrategy simReuseStrategy
) {
  public WorkerAppConfiguration {
    Objects.requireNonNull(merlinFileStore);
    Objects.requireNonNull(store);
    Objects.requireNonNull(untruePlanStart);
    Objects.requireNonNull(simReuseStrategy);
  }
}
