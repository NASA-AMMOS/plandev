package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.concurrent.Executor;

/**
 * A factory for creating fresh copies of a task. All tasks created by a factory must be observationally equivalent.
 *
 * @param <Output>
 *   The type of data returned by a task created by this factory.
 */
public interface TaskFactory<Output> {
  Task<Output> create(Executor executor);

  static TaskFactory<Unit> delaying(Duration duration) {
    return executor -> Task.delaying(duration);
  }

  default <NewOutput> TaskFactory<NewOutput> andThen(TaskFactory<NewOutput> task) {
    return executor -> {
      final var task1 = this.create(executor);
      final var task2 = task.create(executor);

      return task1.andThen(task2);
    };
  }
}
