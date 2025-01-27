package gov.nasa.jpl.aerie.contrib.streamline;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registration;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks.Clock.clock;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

public final class StreamlineSystem {
    private static Resource<Clock> CLOCK;

    private StreamlineSystem() {}

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
    public static void init(
            final gov.nasa.jpl.aerie.merlin.framework.Registrar baseRegistrar,
            final Registrar.ErrorBehavior errorBehavior) {
        CLOCK = resource(clock(ZERO));
        Logging.init(baseRegistrar);
        Registration.init(baseRegistrar, errorBehavior);
    }

    /**
     * Variation on {@link StreamlineSystem#init} for unit testing.
     * Fills in most arguments with defaults suitable for testing.
     */
    public static void testInit(
            final gov.nasa.jpl.aerie.merlin.framework.Registrar baseRegistrar) {
        init(baseRegistrar, Registrar.ErrorBehavior.Throw);
    }

    public static Duration currentTime() {
        return currentValue(CLOCK);
    }
}
