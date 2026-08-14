package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class ReplayingTask<Return> implements Task<Return> {
  private final Scoped<Context> rootContext;
  private final Supplier<Return> task;

  private final ReplayingReactionContext.Memory memory = new ReplayingReactionContext.Memory(new ArrayList<>(), new MutableInt(0));

  public ReplayingTask(final Scoped<Context> rootContext, final Supplier<Return> task) {
    this.rootContext = Objects.requireNonNull(rootContext);
    this.task = Objects.requireNonNull(task);
  }

  @Override
  public TaskStatus<Return> step(final Scheduler scheduler) {
    final var handle = new ReplayingTaskHandle();
    final var context = new ReplayingReactionContext(this.rootContext, this.memory, scheduler, handle);

    try (final var restore = this.rootContext.set(context)){
      final var returnValue = this.task.get();

      // If we get here, the activity has completed normally.
      return TaskStatus.completed(returnValue);
    } catch (final Yield ignored) {
      // If we get here, the activity has suspended.
      return Objects.requireNonNull(handle.status, "Task status is null, but it should have been assigned a value on yield.");
    }
  }

  private final class ReplayingTaskHandle implements TaskHandle {
    public TaskStatus<Return> status = null;

    private Scheduler yield(final TaskStatus<Return> status) {
      this.status = status;
      throw Yield;
    }

    @Override
    public Scheduler delay(final Duration delay) {
      return this.yield(TaskStatus.delayed(delay, ReplayingTask.this));
    }

    @Override
    public Scheduler call(final InSpan inSpan, final TaskFactory<?> child) {
      return this.yield(TaskStatus.calling(inSpan, child, ReplayingTask.this));
    }

    @Override
    public Scheduler await(final gov.nasa.jpl.aerie.merlin.protocol.model.Condition condition) {
      return this.yield(TaskStatus.awaiting(condition, ReplayingTask.this));
    }
  }

  // Since this exception is just used to transfer control out of an activity,
  //   we can pre-allocate a single instance as a unique token
  //   to avoid some of the overhead of exceptions
  //   (most notably the call stack snapshotting).
  private static final class Yield extends RuntimeException {}
  private static final Yield Yield = new Yield();

  @Override
  public Task<Return> duplicate(Executor executor) {
    final ReplayingTask<Return> replayingTask = new ReplayingTask<>(rootContext, task);
    replayingTask.memory.reads().addAll(this.memory.reads());
    replayingTask.memory.writes().setValue(this.memory.writes());
    return replayingTask;
  }
}
