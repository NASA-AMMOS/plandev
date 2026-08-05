package gov.nasa.jpl.aerie.contrib.streamline;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registration;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.ClockResources;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.InstantClock;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.InstantClockResources;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.Objects;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;

public final class StreamlineSystem {
    private static Resource<Clock> CLOCK;
    private static Resource<InstantClock> ABSOLUTE_CLOCK;

    private StreamlineSystem() {}

    /**
     * Arguments required for {@link StreamlineSystem#init}, packaged into an object for easier handling.
     * <p>
     *     Can be constructed directly, or through {@link InitArgs#builder}.
     * </p>
     */
    public record InitArgs(
            gov.nasa.jpl.aerie.merlin.framework.Registrar baseRegistrar,
            Registrar.ErrorBehavior errorBehavior,
            Instant planStart) {
        /**
         * Returns a blank {@link Builder}.
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns a {@link Builder} with some fields set to defaults generally appropriate for testing.
         */
        public static Builder testBuilder() {
            return builder()
                    .errorBehavior(Registrar.ErrorBehavior.Throw)
                    .planStart(Instant.EPOCH);
        }

        public static class Builder {
            private gov.nasa.jpl.aerie.merlin.framework.Registrar baseRegistrar;
            private Registrar.ErrorBehavior errorBehavior;
            private Instant planStart;

            private Builder() {}

            public Builder baseRegistrar(final gov.nasa.jpl.aerie.merlin.framework.Registrar baseRegistrar) {
                this.baseRegistrar = baseRegistrar;
                return this;
            }

            public Builder errorBehavior(final Registrar.ErrorBehavior errorBehavior) {
                this.errorBehavior = errorBehavior;
                return this;
            }

            public Builder planStart(final Instant planStart) {
                this.planStart = planStart;
                return this;
            }

            public InitArgs build() {
                return new InitArgs(
                        Objects.requireNonNull(baseRegistrar, "baseRegistrar must be set"),
                        Objects.requireNonNull(errorBehavior, "errorBehavior must be set"),
                        Objects.requireNonNull(planStart, "planStart must be set")
                );
            }
        }

    }

    /**
     * Initialize all streamline singletons.
     * This method should be called once as the first step of creating a model.
     * <p>
     *     This will call the following subordinate initialization methods:
     *     <ul>
     *         <li>{@link Logging#init}</li>
     *         <li>{@link Registration#init}</li>
     *     </ul>
     *     as well as initialize the singletons contained within this class.
     * </p>
     */
    public static void init(InitArgs args) {
        CLOCK = ClockResources.clock();
        ABSOLUTE_CLOCK = InstantClockResources.absoluteClock(args.planStart);
        Logging.init(args.baseRegistrar);
        Registration.init(args.baseRegistrar, args.errorBehavior);
    }

    public static Duration currentTime() {
        return currentValue(CLOCK);
    }

    public static Resource<Clock> simulationClock() {
        return CLOCK;
    }

    public static Instant currentInstant() {
        return currentValue(ABSOLUTE_CLOCK);
    }

    public static Resource<InstantClock> absoluteClock() {
        return ABSOLUTE_CLOCK;
    }
}
