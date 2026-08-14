package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

public record Selector<Effect>(SelectorRow<?, Effect>... rows) {
  @SafeVarargs
  public Selector {}

  public <EventType> Selector(final Topic<EventType> topic, final Function<EventType, Effect> transform) {
    this(new SelectorRow<>(topic, transform));
  }

  public Optional<Effect> select(final EffectTrait<Effect> trait, final Event event) {
    // Bail out as fast as possible if we're in a trivial (and incredibly common) case.
    if (this.rows.length == 1) return this.rows[0].select(event);
    else if (this.rows.length == 0) return Optional.empty();

    var iter = 0;
    var accumulator = this.rows[iter++].select(event);
    while (iter < this.rows.length) {
      final var effect = this.rows[iter++].select(event);

      if (effect.isEmpty()) continue;
      else if (accumulator.isEmpty()) accumulator = effect;
      else accumulator = Optional.of(trait.concurrently(accumulator.get(), effect.get()));
    }

    return accumulator;
  }

  public boolean matchesAny(final Collection<Topic<?>> topics) {
    // Bail out as fast as possible if we're in a trivial (and incredibly common) case.
    if (this.rows.length == 1) return topics.contains(this.rows[0].topic());

    for (final var row : this.rows) {
      if (topics.contains(row.topic)) return true;
    }
    return false;
  }

  public record SelectorRow<EventType, Effect>(Topic<EventType> topic, Function<EventType, Effect> transform) {
    public Optional<Effect> select(final Event event$) {
      return event$.extract(this.topic, this.transform);
    }
  }
}
