package gov.nasa.jpl.aerie.merlin.server;

import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationResultsHandle;

import java.util.function.Consumer;

public final class ResultsProtocol {
  private ResultsProtocol() {}

  public sealed interface State {
    /** Simulation in enqueued. */
    record Pending(long simulationDatasetId) implements State {}

    /** Simulation in progress, but no results to share yet. */
    record Incomplete(long simulationDatasetId) implements State {}

    /** Simulation complete -- results now available. */
    record Success(long simulationDatasetId, SimulationResultsHandle results) implements State {}

    /** Simulation failed -- don't try to re-run without changing some of the inputs. */
    record Failed(long simulationDatasetId, SimulationFailure reason) implements State {}
  }

  public interface ReaderRole {
    State get();

    /** After calling cancel, `get` is no longer legal to invoke. */
    void cancel();
  }

  public interface WriterRole {
    boolean isCanceled();

    // If the writer aborts because it observes `isCanceled()`,
    //   it must still complete with `failWith()`.
    //   Otherwise, the reader would not be able to reclaim unique ownership
    //   of the underlying resource in order to deallocate it.
    void succeedWith(SimulationResults results);

    void failWith(SimulationFailure reason);

    default void failWith(final Consumer<SimulationFailure.Builder> builderConsumer) {
      final var builder = new SimulationFailure.Builder();
      builderConsumer.accept(builder);
      failWith(builder.build());
    }

    void reportIncompleteResults(SimulationResults results);

    void reportSimulationExtent(Duration extent);
  }

  public interface OwnerRole extends ReaderRole, WriterRole {}
}
