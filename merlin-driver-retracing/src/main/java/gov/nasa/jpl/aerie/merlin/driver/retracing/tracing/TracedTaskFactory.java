package gov.nasa.jpl.aerie.merlin.driver.retracing.tracing;

import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;

import java.util.concurrent.Executor;

public class TracedTaskFactory<T> implements TaskFactory<T> {
  private final TaskTrace<T> trace;

  public TracedTaskFactory(TaskFactory<T> taskFactory) {
    this.trace = TaskTrace.root(taskFactory);
  }

  @Override
  public Task<T> create(final Executor executor) {
    final var task = new TraceCursor<>(trace);
    trace.executor.setValue(executor);
    return task;
  }
}
