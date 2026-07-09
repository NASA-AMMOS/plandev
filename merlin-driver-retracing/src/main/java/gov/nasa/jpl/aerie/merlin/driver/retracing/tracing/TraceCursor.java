package gov.nasa.jpl.aerie.merlin.driver.retracing.tracing;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * A cursor into a task trace. The cursor starts at the beginning and iterates over actions. At each read, it selects
 * a single branch and continues down it. When it reaches an unfinished trace, it calls step to move the trace forward.
 */
public class TraceCursor<T> implements Task<T> {
  private TaskTrace<T> trace;
  private int traceCounter;

  public TraceCursor(TaskTrace<T> trace) {
    this.trace = trace;
  }

  public void update(TaskTrace<T> trace) {
    this.trace = trace;
    this.traceCounter = trace.actions.size();
  }

  public TaskStatus<T> step(Scheduler scheduler) {
    return withContinuation(stepInner(scheduler), this);
  }

  /**
   * Returns an Action.Status rather than a TaskStatus because we want to strip out the continuation
   */
  private Action.Status<T> stepInner(Scheduler scheduler) {
    while (true) {
      List<Action<T>> actions = this.trace.actions;
      while (traceCounter < actions.size()) {
        final var action = actions.get(traceCounter);
        traceCounter++;
        switch (action) {
          case Action.Yield<T> a -> { return a.taskStatus(); }
          case Action.Emit<T, ?> a -> a.apply(scheduler);
          case Action.Spawn<T> a -> scheduler.spawn(a.childSpan(), a.child());
        }
      }

      switch (this.trace.end) {
        case TaskTrace.End.Exit<T> e -> { return new Action.Status.Completed<>(e.returnValue()); }
        case TaskTrace.End.Unfinished<T> e -> {
          return Action.Status.of(this.trace.step(scheduler, this));
        }
        case TaskTrace.End.Read<T> read -> {
          // Read the current value and use it to decide whether to continue down a trace, or start a new one
          final var readValue = scheduler.get(read.query()); // TODO can we avoid performing this read if we know the cell value is unchanged?
          Optional<TaskTrace<T>> foundTrace = read.lookup(readValue);
          if (foundTrace.isPresent()) {
            this.trace = foundTrace.get();
            this.traceCounter = 0;
            continue;
          } else {
            final TaskResumptionInfo<T> resumptionInfo = read.info().duplicate();
            resumptionInfo.reads().add(readValue);
            final var rest = new TaskTrace<>(this.trace.executor, resumptionInfo);
            read.entries().add(new TaskTrace.End.Read.Entry<>(readValue, readValue.toString(), rest));
            return Action.Status.of(rest.step(scheduler, this)); // This will mutate this.trace
          }
        }
      }
    }
  }

  private static <Output> TaskStatus<Output> withContinuation(Action.Status<Output> status, Task<Output> continuation) {
    switch (status) {
      case Action.Status.Completed<Output> s -> {
        return new TaskStatus.Completed<>(s.returnValue());
      }
      case Action.Status.Delayed<Output> s -> {
        return new TaskStatus.Delayed<>(s.delay(), continuation);
      }
      case Action.Status.CallingTask<Output> s -> {
        return new TaskStatus.CallingTask<>(s.childSpan(), s.child(), continuation);
      }
      case Action.Status.AwaitingCondition<Output> s -> {
        return new TaskStatus.AwaitingCondition<>(s.condition(), continuation);
      }
    }
  }

  @Override
  public void release() {
    this.trace.release();
  }
}
