package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/* package-local */
final class ThreadedReactionContext implements Context {
  private final Scoped<Context> rootContext;
  private final TaskHandle handle;
  private Scheduler scheduler;
  private final Consumer<Object> readLogger;

  public ThreadedReactionContext(
      final Scoped<Context> rootContext,
      final Scheduler scheduler,
      final TaskHandle handle,
      final Consumer<Object> readLog)
  {
    this.rootContext = Objects.requireNonNull(rootContext);
    this.scheduler = scheduler;
    this.handle = handle;
    this.readLogger = readLog;
  }

  @Override
  public ContextType getContextType() {
    return ContextType.Reacting;
  }

  @Override
  public <State> State ask(final CellId<State> cellId) {
    final State state = this.scheduler.get(cellId);
    this.readLogger.accept(state);
    return state;
  }

  @Override
  public <Event, Effect, State>
  CellId<State> allocate(
      final State initialState,
      final CellType<Effect, State> cellType,
      final Function<Event, Effect> interpretation,
      final Topic<Event> topic)
  {
    throw new IllegalStateException("Cannot allocate during simulation");
  }

  @Override
  public <Event> void emit(final Event event, final Topic<Event> topic) {
    this.scheduler.emit(event, topic);
  }

  @Override
  public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
    this.scheduler.startActivity(activity, inputTopic);
  }

  @Override
  public <T> void endActivity(final T result, final Topic<T> outputTopic) {
    this.scheduler.endActivity(result, outputTopic);
  }

  @Override
  public void spawn(final InSpan inSpan, final TaskFactory<?> task) {
    this.scheduler.spawn(inSpan, task);
  }

  @Override
  public <T> void call(final InSpan inSpan, final TaskFactory<T> task) {
    this.scheduler = null;  // Relinquish the current scheduler before yielding, in case an exception is thrown.
    this.scheduler = this.handle.call(inSpan, task);
  }

  @Override
  public void delay(final Duration duration) {
    this.scheduler = null;  // Relinquish the current scheduler before yielding, in case an exception is thrown.
    this.scheduler = this.handle.delay(duration);
  }

  @Override
  public void waitUntil(final Condition condition) {
    this.scheduler = null;  // Relinquish the current scheduler before yielding, in case an exception is thrown.
    this.scheduler = this.handle.await((now, atLatest) -> {
      try (final var restore = this.rootContext.set(new QueryContext(now))) {
        return condition.nextSatisfied(true, Duration.ZERO, atLatest);
      }
    });
  }
}
