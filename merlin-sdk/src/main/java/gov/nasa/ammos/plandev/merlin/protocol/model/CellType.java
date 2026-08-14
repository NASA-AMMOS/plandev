package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Optional;

public interface CellType<Effect, State> {
  EffectTrait<Effect> getEffectType();

  State duplicate(State state);
  void apply(State state, Effect effect);

  default void step(final State state, final Duration duration) {
    // Unless specified, a cell is unaffected by the passage of time.
  }

  /** Get the (positive) amount of time that this cell is valid for, or empty if it's valid forever. */
  default Optional<Duration> getExpiry(final State state) {
    return Optional.empty();
  }
}
