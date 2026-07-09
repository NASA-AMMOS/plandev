package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/* package-local */
final class ReplayingReactionContext implements Context {
  private final Scoped<Context> rootContext;
  private final TaskHandle handle;
  private Scheduler scheduler;

  private final MemoryCursor memory;

  public ReplayingReactionContext(
      final Scoped<Context> rootContext,
      final Memory memory,
      final Scheduler scheduler,
      final TaskHandle handle)
  {
    this.rootContext = Objects.requireNonNull(rootContext);
    this.memory = new MemoryCursor(memory, new MutableInt(0), new MutableInt(0));
    this.scheduler = scheduler;
    this.handle = handle;
  }

  @Override
  public ContextType getContextType() {
    return ContextType.Reacting;
  }

  @Override
  public <State> State ask(final CellId<State> cellId) {
    return this.memory.doOnce(() -> {
      return this.scheduler.get(cellId);
    });
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
    this.memory.doOnce(() -> {
      this.scheduler.emit(event, topic);
    });
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
    this.memory.doOnce(() -> {
      this.scheduler.spawn(inSpan, task);
    });
  }

  @Override
  public <T> void call(final InSpan inSpan, final TaskFactory<T> task) {
    this.memory.doOnce(() -> {
      this.scheduler = null;  // Relinquish the current scheduler before yielding, in case an exception is thrown.
      this.scheduler = this.handle.call(inSpan, task);
    });
  }

  @Override
  public void delay(final Duration duration) {
    this.memory.doOnce(() -> {
      this.scheduler = null;  // Relinquish the current scheduler before yielding, in case an exception is thrown.
      this.scheduler = this.handle.delay(duration);
    });
  }

  @Override
  public void waitUntil(final Condition condition) {
    this.memory.doOnce(() -> {
      this.scheduler = null;  // Relinquish the current scheduler before yielding, in case an exception is thrown.
      this.scheduler = this.handle.await((now, atLatest) -> {
        try (final var restore = this.rootContext.set(new QueryContext(now))) {
          return condition.nextSatisfied(true, Duration.ZERO, atLatest);
        }
      });
    });
  }

  public record Memory(List<Object> reads, MutableInt writes) {
    public void clear() {
      this.reads().clear();
      this.writes().setValue(0);
    }
  }

  private record MemoryCursor(Memory memory, MutableInt nextRead, MutableInt nextWrite) {
    public void doOnce(final Runnable action) {
      if (!hasCachedWrite()) {
        // Flag a write *before* we run, because we'll likely yield out via exception.
        this.memory.writes().add(1);
        action.run();
      }

      this.nextWrite.add(1);
    }

    public <T> T doOnce(final Supplier<T> action) {
      final T value;
      if (!hasCachedRead()) {
        value = action.get();
        this.memory.reads().add(value);
      } else {
        // SAFETY: Tasks are deterministic, and last time we did this action, we cached a T.
        @SuppressWarnings("unchecked")
        final var cachedValue = (T) this.memory.reads().get(this.nextRead.getValue());
        value = cachedValue;
      }

      this.nextRead.add(1);
      return value;
    }

    private boolean hasCachedRead() {
      return (this.nextRead.getValue() < this.memory.reads().size());
    }

    private boolean hasCachedWrite() {
      return (this.nextWrite.getValue() < this.memory.writes().getValue());
    }
  }
}
