package gov.nasa.jpl.aerie.merlin.driver.retracing.tracing;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Records all the information necessary to resume a task at a particular step. This involves creating a new task using
 * the task factory, and stepping it numSteps times, using the reads list to respond to any read requests
 */
public record TaskResumptionInfo<T>(TaskFactory<T> taskFactory, MutableInt numSteps, List<Object> reads) {
  public static <T> TaskResumptionInfo<T> init(TaskFactory<T> taskFactory) {
    return new TaskResumptionInfo<>(taskFactory, new MutableInt(0), new ArrayList<>());
  }
  TaskResumptionInfo<T> duplicate() {
    return new TaskResumptionInfo<>(taskFactory, new MutableInt(numSteps), new ArrayList<>(reads));
  }

  public boolean isEmpty() {
    return this.reads().isEmpty() && this.numSteps().getValue() == 0;
  }

  /**
   * NOTE: After the final read has been performed, all subsequent actions will be forwarded to the scheduler
   */
  @SuppressWarnings("unchecked")
  public TaskStatus<T> restart(Scheduler scheduler, Executor executor) {
    if (this.isEmpty()) {
      return this.taskFactory.create(executor).step(scheduler);
    }

    final var reads = this.reads();
    final var numSteps = this.numSteps().getValue();
    Task<T> task = this.taskFactory().create(executor);
    final var readIterator = new ArrayList<>(reads).iterator();
    TaskStatus<T> taskStatus = null;
    for (int i = 0; i < numSteps + 1; i++) {
      taskStatus = task.step(new Scheduler() {
        @Override
        public <State> State get(final CellId<State> cellId) {
          if (readIterator.hasNext()) {
            return (State) readIterator.next();
          } else {
            return scheduler.get(cellId);
          }
        }

        @Override
        public <Event> void emit(final Event event, final Topic<Event> topic) {
          if (!readIterator.hasNext()) {
            scheduler.emit(event, topic);
          }
        }

        @Override
        public void spawn(InSpan childSpan, final TaskFactory<?> task) {
          if (!readIterator.hasNext()) {
            scheduler.spawn(childSpan, task);
          }
        }

        @Override
        public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
          if (!readIterator.hasNext()) {
            scheduler.startActivity(activity, inputTopic);
          }
        }

        @Override
        public <T> void endActivity(final T result, final Topic<T> outputTopic) {
          if (!readIterator.hasNext()) {
            scheduler.endActivity(result, outputTopic);
          }
        }
      });
    }
    return Objects.requireNonNull(taskStatus);
  }
}
