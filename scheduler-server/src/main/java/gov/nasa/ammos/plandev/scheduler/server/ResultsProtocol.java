package gov.nasa.jpl.aerie.scheduler.server;

import java.util.Optional;
import java.util.function.Consumer;

import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleFailure;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;

/**
 * interfaces used to coordinate parties interested in the scheduling results
 *
 * (following example of merlin.server.ResultsProtocol)
 */
//TODO: see if this could reduce to some flavor of a java.util.concurrent.CompletableFuture<>
public final class ResultsProtocol {
  private ResultsProtocol() {}

  /**
   * common interface for different possible results of scheduling
   */
  //TODO: unify with ScheduleAction.Response which has overlapping options/data
  public sealed interface State {

    /**
     * @return id associated with this run of the scheduler
     */
    long analysisId();

    /** Scheduling in enqueued. */
    record Pending(long analysisId) implements State {}

    /**
     * scheduling in progress, full results not yet available
     */
    //TODO: could probably provide some partial results
    record Incomplete(long analysisId) implements State {}

    /**
     * scheduling completed successfully, contains the full results
     *
     * @param results the results of the scheduling run
     */
    record Success(ScheduleResults results, long analysisId, Optional<Long> datasetId) implements State {}

    /**
     * scheduling failed; likely need to change inputs before re-running
     *
     * @param reason description of why the scheduling operation failed
     */
    record Failed(ScheduleFailure reason, long analysisId) implements State {}
  }

  /**
   * observer for a schedule result
   */
  public interface ReaderRole {

    /**
     * retrieve the current status of the scheduling run, including if it is still in progress
     *
     * the scheduling run must not have been previously cancel()ed
     *
     * @return the status of the scheduling run
     */
    State get();

    /**
     * cancel the interest in the result of the scheduling run
     *
     * it is illegal to call get() after a cancel() call
     */
    //TODO: determine if this also kills the actual run itself (ie should plan possibly mutate after a cancel?)
    void cancel();
  }

  /**
   * producer for a scheduling result
   */
  public interface WriterRole {
    /**
     * mark the scheduling run as fully complete and attach the given results
     *
     * @param results the summary results of the scheduling run, including satisfaction metrics etc
     */
    void succeedWith(ScheduleResults results, Optional<DatasetId> datasetId);

    /**
     * Mark that the scheduler has acknowledged the cancellation
     */
    void reportCanceled(final SchedulingInterruptedException e);

    /**
     * mark the scheduling run as having failed with the given reason
     *
     * @param reason the reason that the scheduling run failed
     */
    void failWith(ScheduleFailure reason);

    default void failWith(final Consumer<ScheduleFailure.Builder> builderConsumer) {
      final var builder = new ScheduleFailure.Builder();
      builderConsumer.accept(builder);
      failWith(builder.build());
    }
  }

  /**
   * interface that can both read and write to a scheduling result object
   */
  public interface OwnerRole extends ReaderRole, WriterRole {}
}
