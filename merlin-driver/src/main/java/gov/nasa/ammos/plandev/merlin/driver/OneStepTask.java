package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;

import java.util.concurrent.Executor;
import java.util.function.Function;

public record OneStepTask<T>(Function<Scheduler, TaskStatus<T>> f) implements Task<T> {
  @Override
  public TaskStatus<T> step(final Scheduler scheduler) {
    return f.apply(scheduler);
  }

  @Override
  public Task<T> duplicate(Executor executor) {
    return this;
  }
}
