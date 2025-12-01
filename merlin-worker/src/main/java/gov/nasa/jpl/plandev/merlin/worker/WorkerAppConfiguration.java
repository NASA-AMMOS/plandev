package gov.nasa.jpl.plandev.merlin.worker;

import gov.nasa.jpl.plandev.merlin.server.config.Store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record WorkerAppConfiguration(
    Path merlinFileStore,
    Store store,
    long simulationProgressPollPeriodMillis,
    Instant untruePlanStart
) {
  public WorkerAppConfiguration {
    Objects.requireNonNull(merlinFileStore);
    Objects.requireNonNull(store);
    Objects.requireNonNull(untruePlanStart);
  }
}
