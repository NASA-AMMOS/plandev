package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Binds the state of a cell together with its dynamical behavior. */
public final class Cell<State> {
  private final GenericCell<?, State> inner;
  private final State state;

  public boolean doneStepping = false;

  private <Effect> Cell(final GenericCell<Effect, State> inner, final State state) {
    this.inner = inner;
    this.state = state;
  }

  public <Effect> Cell(
      final CellType<Effect, State> cellType,
      final Selector<Effect> selector,
      final EventGraphEvaluator evaluator,
      final State state
  ) {
    this(new GenericCell<>(cellType, cellType.getEffectType(), selector, evaluator), state);
  }

  public Cell<State> duplicate() {
    return new Cell<>(this.inner, this.inner.cellType.duplicate(this.state));
  }

  public void step(final Duration delta) {
    this.inner.cellType.step(this.state, delta);
  }

  /**
   * Step up the Cell (apply Effects of Events) for one set of Events (an EventGraph) up to a specified last Event
   * @param events the Events that may affect the Cell
   * @param lastEvent a boundary within the graph of Events beyond which Events are not applied
   * @param includeLast whether to apply the Effect of the last Event
   * @return whether {@code lastEvent} was encountered
   */
  public boolean apply(final EventGraph<Event> events, Event lastEvent, boolean includeLast) {
    return this.inner.apply(this.state, events, lastEvent, includeLast);
  }

  public void apply(final Event event) {
    this.inner.apply(this.state, event);
  }

  public void apply(final Event[] events, final int from, final int to) {
    this.inner.apply(this.state, events, from, to);
  }

  public Optional<Duration> getExpiry() {
    return this.inner.cellType.getExpiry(this.state);
  }

  public State getState() {
    return this.inner.cellType.duplicate(this.state);
  }

  public List<Topic<?>> getTopics() {
    return Arrays.stream(this.inner.selector.rows()).map(r -> r.topic()).collect(Collectors.toList());
  }

  public Topic<?> getTopic() {
    var topics = getTopics();
    if (topics != null && topics.size() == 1) {
      return topics.get(0);
    }
    throw(new RuntimeException("No single topic for cell! " + topics));
  }


  public boolean isInterestedIn(final Set<Topic<?>> topics) {
    return this.inner.selector.matchesAny(topics);
  }

  @Override
  public String toString() {
    return "@" + hashCode() + ":" + this.state;
  }

  private record GenericCell<Effect, State> (
      CellType<Effect, State> cellType,
      EffectTrait<Effect> algebra,
      Selector<Effect> selector,
      EventGraphEvaluator evaluator
  ) {
    public boolean apply(final State state, final EventGraph<Event> events, Event lastEvent, boolean includeLast) {
      var result = this.evaluator.evaluate(this.algebra, this.selector, events, lastEvent, includeLast);
      final var effect$ = result.getLeft();
      if (effect$.isPresent()) this.cellType.apply(state, effect$.get());
      return result.getRight();
    }

    public void apply(final State state, final Event event) {
      final var effect$ = this.selector.select(this.algebra, event);
      if (effect$.isPresent()) {
        this.cellType.apply(state, effect$.get());
      }
    }

    public void apply(final State state, final Event[] events, int from, final int to) {
      while (from < to) {
        apply(state, events[from++]);
      }
    }
  }
}
