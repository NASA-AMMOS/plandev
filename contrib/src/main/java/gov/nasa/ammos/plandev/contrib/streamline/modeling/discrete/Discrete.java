package gov.nasa.ammos.plandev.contrib.streamline.modeling.discrete;

import gov.nasa.ammos.plandev.contrib.streamline.core.Dynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

public record Discrete<V>(V extract) implements Dynamics<V, Discrete<V>> {
  @Override
  public Discrete<V> step(Duration t) {
    return this;
  }

  public static <V> Discrete<V> discrete(V value) {
    return new Discrete<>(value);
  }
}
