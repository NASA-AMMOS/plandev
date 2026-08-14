package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.function.TriFunction;

import java.util.function.BiFunction;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

/**
 * Dynamics with no observable structure.
 * While very general, these need to be approximated by more structured
 * dynamics to report out to Aerie.
 */
public interface Unstructured<T> extends Dynamics<T, Unstructured<T>> {
  static <T> Unstructured<T> constant(T value) {
    return new Unstructured<T>() {
      @Override
      public T extract() {
        return value;
      }

      @Override
      public Unstructured<T> step(Duration t) {
        return this;
      }
    };
  }

  static <T> Unstructured<T> timeBased(Function<Duration, T> valueOverTime) {
    return new Unstructured<T>() {
      @Override
      public T extract() {
        return valueOverTime.apply(ZERO);
      }

      @Override
      public Unstructured<T> step(final Duration t) {
        return timeBased(valueOverTime.compose(t::plus));
      }
    };
  }

  static <T, D extends Dynamics<T, D>> Unstructured<T> unstructured(D dynamics) {
    return new Unstructured<>() {
      @Override
      public T extract() {
        return dynamics.extract();
      }

      @Override
      public Unstructured<T> step(Duration t) {
        return unstructured(dynamics.step(t));
      }
    };
  }
}
