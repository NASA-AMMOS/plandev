package gov.nasa.jpl.aerie.merlin.driver.retracing.tracing;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;

public sealed interface Action<T> {
  record Emit<T, Event>(Event event, Topic<Event> topic) implements Action<T> {

    void apply(Scheduler scheduler) {
      scheduler.emit(event, topic);
    }

    @Override
    public String toString() {
      return "emit(event=" + event + ", topic=" + topic + ")";
    }
  }

  record Yield<T>(Status<T> taskStatus) implements Action<T> {
    public Yield(final TaskStatus<T> taskStatus) {
      this(Status.of(taskStatus));
    }

    @Override
    public String toString() {
      return switch (taskStatus) {
        case Status.Completed<?> s -> "Completed(" + s.returnValue().toString() + ")";
        case Status.Delayed<?> s -> "delay(" + s.delay().toString() + ")";
        case Status.CallingTask<?> s -> "call(" + s.child().toString() + ")";
        case Status.AwaitingCondition<?> s -> "waitUntil(" + s.condition().toString() + ")";
      };
    }
  }

  record Spawn<T>(InSpan childSpan, TaskFactory<?> child) implements Action<T> {}

  /* Avoid saving Tasks, since those are ephemeral. TaskFactories are OK to save */
  sealed interface Status<Return> {
    record Completed<Return>(Return returnValue) implements Status<Return> {}
    record Delayed<Return>(Duration delay) implements Status<Return> {}
    record CallingTask<Return>(InSpan childSpan, TaskFactory<?> child)
        implements Status<Return> {}
    record AwaitingCondition<Return>(Condition condition) implements Status<Return> {}

    static <Return> Status<Return> of(TaskStatus<Return> taskStatus) {
      return switch (taskStatus) {
        case TaskStatus.AwaitingCondition<Return> v -> new Status.AwaitingCondition<>(v.condition());
        case TaskStatus.CallingTask<Return> v -> new Status.CallingTask<>(v.childSpan(), v.child());
        case TaskStatus.Completed<Return> v -> new Status.Completed<>(v.returnValue());
        case TaskStatus.Delayed<Return> v -> new Status.Delayed<>(v.delay());
      };
    }
  }
}
