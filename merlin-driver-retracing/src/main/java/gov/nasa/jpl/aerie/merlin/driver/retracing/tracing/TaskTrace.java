package gov.nasa.jpl.aerie.merlin.driver.retracing.tracing;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Represents the tree of actions taken by a particular task factory
 */
public class TaskTrace<T> {
  public List<Action<T>> actions = new ArrayList<>();
  public End<T> end;
  public MutableObject<Executor> executor;

  public TaskTrace(MutableObject<Executor> executor, final TaskResumptionInfo<T> info) {
    this.end = new End.Unfinished<>(info);
    this.executor = executor;
  }

  public static <T> TaskTrace<T> root(TaskFactory<T> rootTask) {
    return new TaskTrace<>(new MutableObject<>(null), TaskResumptionInfo.init(rootTask));
  }

  public void add(Action<T> entry) {
    this.actions.add(entry);
  }

  public void exit(End.Exit<T> exit) {
    if (!(this.end instanceof End.Unfinished)) throw new IllegalStateException();
    this.end = exit;
  }

  public <ReturnedType> TaskTrace<T> read(CellId<?> query, ReturnedType value) {
    if (!(this.end instanceof End.Unfinished<T> ending)) throw new IllegalStateException();
    final TaskResumptionInfo<T> resumptionInfoUpToThisPoint = ending.info().duplicate(); // Does not include new read
    ending.info().reads().add(value); // Includes new read

    TaskTrace<T> newTip;
    {
      final TaskTrace<T> res = new TaskTrace<>(executor, ending.info()); // newTip include new read
      res.end = ending;
      newTip = res;
    }
    final var readRecords = new ArrayList<End.Read.Entry<T>>();
    readRecords.add(new End.Read.Entry<>(value, value.toString(), newTip));
    this.end = new End.Read<>(query, readRecords, resumptionInfoUpToThisPoint); // End.Read does not include new read
    return newTip;
  }

  TaskStatus<T> step(Scheduler scheduler, TraceCursor<T> cursor) {
    if (!(this.end instanceof End.Unfinished<T> unfinished)) throw new IllegalStateException();
    return unfinished.step(this, scheduler, cursor, unfinished.info(), this.executor.getValue());
  }

  public sealed interface End<T> {
    record Read<T>(CellId<?> query, List<Read.Entry<T>> entries, TaskResumptionInfo<T> info) implements
        End<T>
    {
      public record Entry<T>(Object value, String string, TaskTrace<T> rest) {}
      public <ReadValue> Optional<TaskTrace<T>> lookup(ReadValue readValue) {
        for (final var readRecord : entries()) {
          if (Objects.equals(readRecord.value(), readValue)) {
            return Optional.of(readRecord.rest);
          }
        }
        return Optional.empty();
      }
    }

    record Exit<T>(T returnValue) implements End<T> {}

    /**
     * Represents an unfinished task trace, and can be used to extend the task trace.
     *
     * It is live if it holds a handle to a running task
     */
    final class Unfinished<T> implements End<T> {
      private TraceWriter<T> writer;
      private Task<T> continuation;
      private boolean finished = false;
      private final TaskResumptionInfo<T> info;

      public Unfinished(TaskResumptionInfo<T> info) {
        this.info = info;
      }

      TaskResumptionInfo<T> info() {
        return info;
      }

      private boolean isLive() {
        if ((writer == null || continuation == null) && !(writer == null && continuation == null)) {
          throw new IllegalStateException("Either both writer and continuation should be set, or neither");
        }
        return !(writer == null);
      }

      public TaskStatus<T> step(TaskTrace<T> trace, Scheduler scheduler, TraceCursor<T> cursor, TaskResumptionInfo<T> resumptionInfo, Executor executor) {
        if (this.finished) throw new IllegalStateException("Stepping End.Unfinished after its task has already finished");

        final TaskStatus<T> status;
        if (this.isLive()) {
          resumptionInfo.numSteps().increment();
          status = this.continuation.step(this.writer.instrument(scheduler));
        } else {
          this.writer = new TraceWriter<>(trace);
          status = resumptionInfo.restart(this.writer.instrument(scheduler), executor);
        }
        this.writer.yield(status);
        cursor.update(this.writer.trace);

        {
          final var continuation = extractTask(status);
          if (continuation.isPresent()) {
            this.continuation = continuation.get();
          } else {
            this.writer = null;
            this.continuation = null;
            this.finished = true;
          }
        }

        return status;
      }

      void release() {
        if (this.continuation != null) this.continuation.release();
      }

      private static <T> Optional<Task<T>> extractTask(TaskStatus<T> status) {
        return switch (status) {
          case TaskStatus.AwaitingCondition<T> v -> Optional.of(v.continuation());
          case TaskStatus.CallingTask<T> v -> Optional.of(v.continuation());
          case TaskStatus.Completed<T> v -> Optional.empty();
          case TaskStatus.Delayed<T> v -> Optional.of(v.continuation());
        };
      }
    }
  }

  void release() {
    switch (this.end) {
      case End.Unfinished<T> e -> e.release();

      case End.Exit<T> v -> {
      }
      case End.Read<T> v -> {
        for (final var entry : v.entries) {
          entry.rest.release();
      }
    }
    }

  }

}
