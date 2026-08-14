package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ThreadedTask<Return> implements Task<Return> {
  public static boolean CACHE_READS = false;
  private final boolean cacheReads = CACHE_READS;

  private final Scoped<Context> rootContext;
  private final Supplier<Return> task;
  private final Executor executor;

  private final ArrayBlockingQueue<TaskRequest> hostToTask = new ArrayBlockingQueue<>(1);
  private final ArrayBlockingQueue<TaskResponse<Return>> taskToHost = new ArrayBlockingQueue<>(1);

  private Lifecycle lifecycle = Lifecycle.Inactive;
  private Return returnValue;
  private final List<Object> readLog = new ArrayList<>();
  private int stepCount = 0;

  public ThreadedTask(final Executor executor, final Scoped<Context> rootContext, final Supplier<Return> task) {
    this.rootContext = Objects.requireNonNull(rootContext);
    this.task = Objects.requireNonNull(task);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public TaskStatus<Return> step(final Scheduler scheduler) {
    this.stepCount++;
    try {
      if (this.lifecycle == Lifecycle.Terminated) {
        return TaskStatus.completed(this.returnValue);
      } else if (this.lifecycle == Lifecycle.Inactive) {
        this.lifecycle = Lifecycle.Running;
        beginAsync();
      }

      // TODO: Add a (configurable) timeout to the `take()` call.
      //   It should be sufficiently long as to allow the user-defined task to do its job.
      //   The `put()` call is fine -- we know the thread will immediately wait
      //   for a new request as soon as it puts a response to the last request.
      // TODO: Track metrics for how long a task runs before responding.
      //   This will help to tune the timeout.
      this.hostToTask.put(new TaskRequest.Resume(scheduler));
      final var response = this.taskToHost.take();

      if (response instanceof TaskResponse.Success<Return> r) {
        final var status = r.status;

        if (status instanceof TaskStatus.Completed<Return> s) {
          this.lifecycle = Lifecycle.Terminated;
          this.returnValue = s.returnValue();
        }

        return status;
      } else if (response instanceof TaskResponse.Failure<Return> r) {
        this.lifecycle = Lifecycle.Terminated;

        // We re-throw the received exception to avoid interfering with `catch` blocks
        //   that might be looking for this specific exception, but we add a new exception
        //   to its suppression list to provide a stack trace in this thread, too.
        final var ex = r.failure;
        ex.addSuppressed(new TaskFailureException());

        // This exception shouldn't be a checked exception, but we have to prove it to Java.
        if (ex instanceof RuntimeException runtimeException) {
          throw runtimeException;
        } else if (ex instanceof Error error) {
          throw error;
        } else {
          throw new RuntimeException("Unexpected checked exception escaped from task thread", ex);
        }
      } else {
        throw new Error(String.format(
            "Unexpected variant of %s: %s",
            TaskResponse.class.getCanonicalName(),
            response.getClass().getCanonicalName()));
      }
    } catch (final InterruptedException ex) {
      throw new Error("Merlin host unexpectedly interrupted", ex);
    }
  }

  private void beginAsync() {
    final var handle = new ThreadedTaskHandle();

    if (((ExecutorService) this.executor).isShutdown()) {
      throw new RuntimeException("Executor is shut down!");
    }

    this.executor.execute(() -> {
      final TaskRequest request;
      try {
        request = ThreadedTask.this.hostToTask.take();
      } catch (final InterruptedException ex) {
        throw new Error("Merlin task unexpectedly interrupted", ex);
      }

      TaskResponse<Return> response;
      try {
        response = handle.run(request);
      } catch (final Throwable ex) {
        response = new TaskResponse.Failure<>(ex);
      }

      try {
        ThreadedTask.this.taskToHost.put(response);
      } catch (final InterruptedException ex) {
        throw new Error("Merlin task unexpectedly interrupted", ex);
      }
    });
  }

  @Override
  public void release() {
    if (this.lifecycle == Lifecycle.Running) {
      try {
        // TODO: Add a (configurable) timeout to the `take()` call.
        //   This timeout can be (much) shorter than the one in `ThreadedTask.step()`.
        //   The `put()` call is fine -- we know the thread will immediately wait
        //   for a new request as soon as it puts a response to the last request.
        this.hostToTask.put(new TaskRequest.Abort());
        final var ignored = this.taskToHost.take();
      } catch (final InterruptedException ex) {
        throw new Error("Merlin host unexpectedly interrupted", ex);
      }
    }

    this.lifecycle = Lifecycle.Inactive;
  }

  private final class ThreadedTaskHandle implements TaskHandle {
    private boolean isAborting = false;

    public TaskResponse<Return> run(final TaskRequest request) {
      if (request instanceof TaskRequest.Resume resume) {
        final var scheduler = resume.scheduler;

        final Consumer<Object> readLogger = cacheReads ? ThreadedTask.this.readLog::add : $ -> {};
        final var context = new ThreadedReactionContext(ThreadedTask.this.rootContext, scheduler, this, readLogger);

        try (final var restore = ThreadedTask.this.rootContext.set(context)) {
          return new TaskResponse.Success<>(TaskStatus.completed(ThreadedTask.this.task.get()));
        } catch (final TaskAbort ex) {
          return new TaskResponse.Success<>(TaskStatus.completed(null));
        } catch (final Throwable ex) {
          return new TaskResponse.Failure<>(ex);
        }
      } else if (request instanceof TaskRequest.Abort) {
        return new TaskResponse.Success<>(TaskStatus.completed(null));
      } else {
        throw new Error(String.format(
            "Unexpected variant of %s: %s",
            TaskRequest.class.getCanonicalName(),
            request.getClass().getCanonicalName()));
      }
    }

    private Scheduler yield(final TaskStatus<Return> status) {
      // If we're in the middle of aborting, just keep trying to bail out.
      if (this.isAborting) throw TaskAbort;

      // Get the next request from the driver.
      final TaskRequest request;
      try {
        ThreadedTask.this.taskToHost.put(new TaskResponse.Success<>(status));
        request = ThreadedTask.this.hostToTask.take();
      } catch (final InterruptedException ex) {
        throw new Error("Merlin task unexpectedly interrupted", ex);
      }

      if (request instanceof TaskRequest.Resume resumeRequest) {
        // We've been told to continue executing.
        return resumeRequest.scheduler;
      } else if (request instanceof TaskRequest.Abort) {
        // We've been told to bail out and release this thread ASAP.
        //
        // We'll throw an exception to get as far up and out of the task as we can.
        // If the task intercepts this exception (via `catch` or `finally`),
        // it may attempt to perform more simulation effects.
        // We'll just keep throwing whenever `yield()` is called.
        //
        // The task might also busy-loop without ever passing control back.
        // This would be poor behavior even for an active task, so there's not much
        // we can do except build the driver thread to be resilient against ill-behaved tasks.
        //
        // TODO: Don't let the ReactionContext interact directly with the scheduler.
        //   We should intercept calls to the scheduler so that they, too, cause a `TaskAbort`.
        //   As it stands, they will cause a `NullPointerException`, since `ReactionContext`
        //   sets its `scheduler` field to null before yielding. That's fine -- it keeps us bailing --
        //   but it's not great to have this interaction logic spread out.
        this.isAborting = true;
        throw TaskAbort;
      } else {
        throw new Error(String.format(
            "Unexpected variant of %s: %s",
            TaskRequest.class.getCanonicalName(),
            request.getClass().getCanonicalName()));
      }
    }

    @Override
    public Scheduler delay(final Duration delay) {
      return this.yield(TaskStatus.delayed(delay, ThreadedTask.this));
    }

    @Override
    public Scheduler call(final InSpan inSpan, final TaskFactory<?> child) {
      return this.yield(TaskStatus.calling(inSpan, child, ThreadedTask.this));
    }

    @Override
    public Scheduler await(final gov.nasa.jpl.aerie.merlin.protocol.model.Condition condition) {
      return this.yield(TaskStatus.awaiting(condition, ThreadedTask.this));
    }
  }

  private enum Lifecycle { Inactive, Running, Terminated }

  sealed interface TaskRequest {
    record Resume(Scheduler scheduler) implements TaskRequest {}

    record Abort() implements TaskRequest {}
  }

  sealed interface TaskResponse<Return> {
    record Success<Return>(TaskStatus<Return> status) implements TaskResponse<Return> {}

    record Failure<Return>(Throwable failure) implements TaskResponse<Return> {}
  }

  public static final class TaskFailureException extends RuntimeException {
    public TaskFailureException() {
      super("Observed task thread failure from driver thread");
    }
  }

  private static final TaskAbort TaskAbort = new TaskAbort();
  /**
   * A control-flow exception for quickly aborting a task which will never proceed any further.
   *
   * This exception extends Error instead of RuntimeException to reduce the likelihood that
   * it gets spuriously caught by an over-broad catch clause.
   */
  private static final class TaskAbort extends Error {
    public TaskAbort() {
      super(null, null, /* capture suppressed exceptions? */ true, /* capture stack trace? */ false);
    }
  }

  @Override
  public Task<Return> duplicate(Executor executor) {
    if (!cacheReads) {
      throw new RuntimeException("Cannot duplicate threaded task without cached reads");
    }
    final ThreadedTask<Return> threadedTask = new ThreadedTask<>(executor, rootContext, task);
    final var readIterator = readLog.iterator();
    final Scheduler scheduler = new Scheduler() {
      @Override
      public <State> State get(final CellId<State> cellId) {
        return (State) readIterator.next();
      }

      @Override
      public <Event> void emit(final Event event, final Topic<Event> topic) {

      }

      @Override
      public void spawn(final InSpan childSpan, final TaskFactory<?> task) {

      }
    };
    for (int i = 0; i < stepCount; i++) {
      threadedTask.step(scheduler);
    }
    return threadedTask;
  }

  private static String getEnv(final String key, final String fallback) {
    final var env = System.getenv(key);
    return env == null ? fallback : env;
  }
}
