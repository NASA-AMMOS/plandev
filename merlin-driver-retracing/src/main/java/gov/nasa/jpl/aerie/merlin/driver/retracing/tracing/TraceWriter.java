package gov.nasa.jpl.aerie.merlin.driver.retracing.tracing;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;

public class TraceWriter<T> {
  public TaskTrace<T> trace;

  TraceWriter(TaskTrace<T> trace) {
    this.trace = trace;
  }

  public <ReturnedType> void read(final CellId<?> query, final ReturnedType read) {
    this.trace = this.trace.read(query, read);
  }

  public <Event> void emit(Event event, Topic<Event> topic) {
    this.trace.add(new Action.Emit<>(event, topic));
  }

  public void spawn(InSpan inSpan, TaskFactory<?> child) {
    this.trace.add(new Action.Spawn<>(inSpan, child));
  }

  public void yield(TaskStatus<T> taskStatus) {
    if (taskStatus instanceof TaskStatus.Completed<T> t) {
      this.trace.exit(new TaskTrace.End.Exit<>(t.returnValue()));
    } else {
      this.trace.add(new Action.Yield<>(taskStatus));
    }
  }

  public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
    // TODO
  }

  public <T> void endActivity(final T result, final Topic<T> outputTopic) {
    // TODO
  }

  public Scheduler instrument(Scheduler scheduler) {
    return new Scheduler() {
      @Override
      public <State> State get(final CellId<State> cellId) {
        final State value = scheduler.get(cellId);
        TraceWriter.this.read(cellId, value);
        return value;
      }

      @Override
      public <Event> void emit(final Event event, final Topic<Event> topic) {
        scheduler.emit(event, topic);
        TraceWriter.this.emit(event, topic);
      }

      @Override
      public void spawn(final InSpan taskSpan, final TaskFactory<?> task) {
        scheduler.spawn(taskSpan, task);
        TraceWriter.this.spawn(taskSpan, task);
      }

      @Override
      public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
        scheduler.startActivity(activity, inputTopic);
        TraceWriter.this.startActivity(activity, inputTopic);
      }

      @Override
      public <T> void endActivity(final T result, final Topic<T> outputTopic) {
        scheduler.endActivity(result, outputTopic);
        TraceWriter.this.endActivity(result, outputTopic);
      }
    };
  }
}
