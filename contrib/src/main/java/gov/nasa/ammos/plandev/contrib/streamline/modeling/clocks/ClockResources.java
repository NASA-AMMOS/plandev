package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.signalling;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.bind;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.not;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.map;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.EPSILON;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

public final class ClockResources {
  private ClockResources() {}

  /**
   * Create a clock starting at zero time.
   */
  public static Resource<Clock> clock() {
    return resource(Clock.clock(ZERO));
  }

  public static Resource<Discrete<Boolean>> lessThan(Resource<Clock> clock, Resource<Discrete<Duration>> threshold) {
    return signalling(bind(clock, threshold, (Clock c, Discrete<Duration> t) -> {
      final Duration crossoverTime = t.extract().minus(c.extract());
      return ResourceMonad.pure(
              crossoverTime.isPositive()
                      ? expiring(discrete(true), crossoverTime)
                      : neverExpiring(discrete(false)));
    }));
  }

  public static Resource<Discrete<Boolean>> lessThanOrEquals(Resource<Clock> clock, Resource<Discrete<Duration>> threshold) {
    // Since Duration is an integral type, implement strictness through EPSILON stepping
    return lessThan(clock, map(threshold, EPSILON::plus));
  }

  public static Resource<Discrete<Boolean>> greaterThan(Resource<Clock> clock, Resource<Discrete<Duration>> threshold) {
    return not(lessThanOrEquals(clock, threshold));
  }

  public static Resource<Discrete<Boolean>> greaterThanOrEquals(Resource<Clock> clock, Resource<Discrete<Duration>> threshold) {
    return not(lessThan(clock, threshold));
  }

  public static Resource<Linear> asLinear(Resource<Clock> clock, Duration unit) {
    return VariableClockResources.asLinear(VariableClockResources.asVariableClock(clock), unit);
  }
}
