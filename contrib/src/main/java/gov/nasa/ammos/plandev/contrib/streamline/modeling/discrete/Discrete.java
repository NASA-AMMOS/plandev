package gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

public record Discrete<V>(V extract) implements Dynamics<V, Discrete<V>> {
  @Override
  public Discrete<V> step(Duration t) {
    return this;
  }

  public static <V> Discrete<V> discrete(V value) {
    return new Discrete<>(value);
  }
}
