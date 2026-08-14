package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.Expiry;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiring.expiring;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.signalling;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.bind;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete.discrete;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.not;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear.linear;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.EPSILON;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

public final class VariableClockResources {
  private VariableClockResources() {}

  public static Resource<Discrete<Boolean>> lessThan(Resource<VariableClock> clock, Resource<Discrete<Duration>> threshold) {
    // Since Duration is an integral type, implement strictness through EPSILON stepping
    return lessThanOrEquals(clock, DiscreteResourceMonad.map(threshold, t -> t.minus(EPSILON)));
  }

  public static Resource<Discrete<Boolean>> lessThanOrEquals(Resource<VariableClock> clock, Resource<Discrete<Duration>> threshold) {
    return signalling(bind(clock, threshold, (VariableClock c, Discrete<Duration> t) -> {
      final boolean result = c.extract().shorterThan(t.extract());
      // If multiplier is zero, or direction of clock is away from threshold, never expires.
      final Expiry expiry;
      if (c.multiplier() == 0 || (result == c.multiplier() < 0)) {
        expiry = Expiry.NEVER;
      } else {
        // ceil( (h - c) / k ) = floor( (h - c - 1) / k ) + 1, where EPSILON = 1 and dividedBy does floor( ... / ... )
        // Define T = h - 1, where h = threshold + 1 if result, or threshold itself if not.
        var T = result ? t.extract() : t.extract().minus(EPSILON);
        expiry = Expiry.at(T.minus(c.extract()).dividedBy(c.multiplier()).plus(EPSILON));
      }
      return ResourceMonad.pure(expiring(discrete(result), expiry));
    }));
  }

  public static Resource<Discrete<Boolean>> greaterThan(Resource<VariableClock> clock, Resource<Discrete<Duration>> threshold) {
    return not(lessThanOrEquals(clock, threshold));
  }

  public static Resource<Discrete<Boolean>> greaterThanOrEquals(Resource<VariableClock> clock, Resource<Discrete<Duration>> threshold) {
    return not(lessThan(clock, threshold));
  }

  public static Resource<Linear> asLinear(Resource<VariableClock> clock, Duration unit) {
    return map(clock, c -> linear(c.extract().ratioOver(unit), c.multiplier() * SECOND.ratioOver(unit)));
  }

  public static Resource<VariableClock> asVariableClock(Resource<Clock> clock) {
    return map(clock, c -> new VariableClock(c.extract(), 1));
  }
}
