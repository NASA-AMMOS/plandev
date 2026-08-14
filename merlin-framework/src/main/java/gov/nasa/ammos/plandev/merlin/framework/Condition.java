package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Optional;

public interface Condition {
  Optional<Duration> nextSatisfied(boolean positive, Duration atEarliest, Duration atLatest);

  default Condition and(final Condition other) {
    return and(this, other);
  }

  default Condition or(final Condition other) {
    return or(this, other);
  }

  default Condition not() {
    return not(this);
  }


  Condition TRUE = (positive, atEarliest, atLatest) -> Optional.of(atEarliest).filter(t -> positive);
  Condition FALSE = not(TRUE);

  static Condition or(final Condition left, final Condition right) {
    return (positive, atEarliest, atLatest) -> {
      if (atLatest.shorterThan(atEarliest)) return Optional.empty();
      if (!positive) return and(not(left), not(right)).nextSatisfied(true, atEarliest, atLatest);

      final var left$ = left.nextSatisfied(true, atEarliest, atLatest);
      final var right$ = right.nextSatisfied(true, atEarliest, left$.orElse(atLatest));

      if (left$.isEmpty()) return right$;
      if (right$.isEmpty()) return left$;
      return Optional.of(Duration.min(left$.get(), right$.get()));
    };
  }

  static Condition and(final Condition left, final Condition right) {
    return (positive, atEarliest, atLatest) -> {
      if (atLatest.shorterThan(atEarliest)) return Optional.empty();
      if (!positive) return or(not(left), not(right)).nextSatisfied(true, atEarliest, atLatest);

      Optional<Duration> left$, right$;

      left$ = left.nextSatisfied(true, atEarliest, atLatest);
      if (left$.isEmpty()) return Optional.empty();

      while (true) {
        atEarliest = left$.get();
        if (atLatest.shorterThan(atEarliest)) break;

        right$ = right.nextSatisfied(true, atEarliest, atLatest);
        if (right$.isEmpty()) break;
        if (right$.get().equals(left$.get())) return left$;

        atEarliest = right$.get();
        if (atLatest.shorterThan(atEarliest)) break;

        left$ = left.nextSatisfied(true, atEarliest, atLatest);
        if (left$.isEmpty()) break;
        if (left$.get().equals(right$.get())) return right$;
      }

      return Optional.empty();
    };
  }

  static Condition not(final Condition base) {
    return (positive, atEarliest, atLatest) ->
        base.nextSatisfied(!positive, atEarliest, atLatest);
  }
}
