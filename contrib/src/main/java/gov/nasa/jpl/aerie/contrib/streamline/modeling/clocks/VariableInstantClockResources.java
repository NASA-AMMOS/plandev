package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.InstantClock.durationBetween;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.constant;

public final class VariableInstantClockResources {
    private VariableInstantClockResources() {}

    public static Resource<VariableClock> relativeTo(Resource<VariableInstantClock> clock, Resource<Discrete<Instant>> zeroTime) {
        return name(map(clock, zeroTime, (c, t) ->
                        new VariableClock(durationBetween(c.extract(), t.extract()), c.multiplier())),
                "%s relative to %s", clock, zeroTime);
    }

    public static Resource<Discrete<Boolean>> lessThan(Resource<VariableInstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return VariableClockResources.lessThan(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<Discrete<Boolean>> lessThanOrEquals(Resource<VariableInstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return VariableClockResources.lessThanOrEquals(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<Discrete<Boolean>> greaterThan(Resource<VariableInstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return VariableClockResources.greaterThan(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<Discrete<Boolean>> greaterThanOrEquals(Resource<VariableInstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return VariableClockResources.greaterThanOrEquals(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<VariableClock> between(Resource<VariableInstantClock> start, Resource<VariableInstantClock> end) {
        return map(start, end, (s, e) -> new VariableClock(durationBetween(s.extract(), e.extract()), e.multiplier() - s.multiplier()));
    }
}
