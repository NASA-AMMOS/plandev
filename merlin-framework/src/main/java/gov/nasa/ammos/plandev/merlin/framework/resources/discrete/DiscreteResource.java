package gov.nasa.jpl.aerie.merlin.framework.resources.discrete;

import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.framework.Resource;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public interface DiscreteResource<T> extends Resource<T> {
  static <T, S>
  DiscreteResource<S> mapped(final DiscreteResource<T> resource, final Function<T, S> transform) {
    Objects.requireNonNull(resource);
    Objects.requireNonNull(transform);

    return () -> transform.apply(resource.getDynamics());
  }


  default T get() {
    return this.getDynamics();
  }

  default <S> DiscreteResource<S> map(final Function<T, S> transform) {
    return DiscreteResource.mapped(this, transform);
  }


  default Condition isOneOf(final Set<T> values) {
    return (positive, atEarliest, atLatest) -> {
      final var dynamics = this.getDynamics();

      return (positive == values.contains(dynamics))
          ? Optional.of(atEarliest)
          : Optional.empty();
    };
  }

  default Condition is(final T value) {
    return this.isOneOf(Set.of(value));
  }
}
