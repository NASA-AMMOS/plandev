package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.*;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.constant;

public class InstantClockResources {
    /**
     * Create an absolute clock that starts now at the given start time.
     */
    public static MutableResource<InstantClock> absoluteClock(Instant startTime) {
        return resource(new InstantClock(startTime));
    }

    public static Resource<InstantClock> addToInstant(Instant zeroTime, Resource<Clock> relativeClock) {
        return addToInstant(constant(zeroTime), relativeClock);
    }

    public static Resource<InstantClock> addToInstant(Resource<Discrete<Instant>> zeroTime, Resource<Clock> relativeClock) {
        return name(
                map(zeroTime, relativeClock, (zero, clock) ->
                        new InstantClock(Duration.addToInstant(zero.extract(), clock.extract()))),
                "%s + %s",
                zeroTime,
                relativeClock);
    }

    public static Resource<Clock> relativeTo(Resource<InstantClock> clock, Resource<Discrete<Instant>> zeroTime) {
        return name(ResourceMonad.map(clock, zeroTime, (c, t) -> new Clock(InstantClock.durationBetween(t.extract(), c.extract()))),
                "%s relative to %s", clock, zeroTime);
    }

    public static Resource<Discrete<Boolean>> lessThan(Resource<InstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return ClockResources.lessThan(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<Discrete<Boolean>> lessThanOrEquals(Resource<InstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return ClockResources.lessThanOrEquals(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<Discrete<Boolean>> greaterThan(Resource<InstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return ClockResources.greaterThan(relativeTo(clock, threshold), constant(Duration.ZERO));
    }

    public static Resource<Discrete<Boolean>> greaterThanOrEquals(Resource<InstantClock> clock, Resource<Discrete<Instant>> threshold) {
        return ClockResources.greaterThanOrEquals(relativeTo(clock, threshold), constant(Duration.ZERO));
    }
}
