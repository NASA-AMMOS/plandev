package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class InitializationContext implements Context {
  private final Initializer builder;

  public InitializationContext(final Initializer builder) {
    this.builder = Objects.requireNonNull(builder);
  }

  public static <T>
  T initializing(final Initializer builder, final Supplier<T> initializer) {
    try (final var restore = ModelActions.context.set(new InitializationContext(builder))) {
      return initializer.get();
    }
  }

  @Override
  public ContextType getContextType() {
    return ContextType.Initializing;
  }

  @Override
  public <State> State ask(final CellId<State> cellId) {
    return this.builder.getInitialState(cellId);
  }

  @Override
  public <Event, Effect, State>
  CellId<State> allocate(
      final State initialState,
      final CellType<Effect, State> cellType,
      final Function<Event, Effect> interpretation,
      final Topic<Event> topic
  ) {
    return this.builder.allocate(initialState, cellType, interpretation, topic);
  }

  @Override
  public <Event> void emit(final Event event, final Topic<Event> topic) {
    throw new IllegalStateException("Cannot update simulation state during initialization");
  }

  @Override
  public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
    throw new IllegalStateException("Cannot start executing an activity state during initialization");
  }

  @Override
  public <T> void endActivity(final T result, final Topic<T> outputTopic) {
    throw new IllegalStateException("Cannot end executing an activity state during initialization");
  }


  @Override
  public void spawn(final InSpan _inSpan, final TaskFactory<?> task) {
    // As top-level tasks, daemons always get their own span.
    // TODO: maybe produce a warning if inSpan is not Fresh in initialization context
    this.builder.daemon(null, task);
  }
  public void spawn(final String taskName, final InSpan _inSpan, final TaskFactory<?> task) {
    this.builder.daemon(taskName, task);
  }

  @Override
  public <Return> void call(final InSpan inSpan, final TaskFactory<Return> task) {
    throw new IllegalStateException("Cannot yield during initialization");
  }

  @Override
  public void delay(final Duration duration) {
    throw new IllegalStateException("Cannot yield during initialization");
  }

  @Override
  public void waitUntil(final Condition condition) {
    throw new IllegalStateException("Cannot yield during initialization");
  }
}
