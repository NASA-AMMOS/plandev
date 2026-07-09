package gov.nasa.ammos.aerie.simulation.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * A Simulator is capable of interpreting a schedule and producing results.
 *
 * The simulate method may be called multiple times with different schedules.
 *
 * Schedule entries that share ids across calls to `simulate` must share a directive type.
 *
 * The first action taken by each directive type must be to call `startActivity`, and the last action must be to call `endActivity`
 */
public interface Simulator {
  default Results simulate(Schedule schedule) {
    return simulate(schedule, () -> false);
  }
  Results simulate(Schedule schedule, Supplier<Boolean> isCancelled);

  interface Factory {
    <Config, Model> Simulator create(ModelType<Config, Model> modelType, Config config, Instant startTime, Duration duration);
  }
}
