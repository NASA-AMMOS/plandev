package gov.nasa.jpl.aerie.merlin.protocol.driver;

import gov.nasa.jpl.aerie.merlin.protocol.Capability;

/**
 * An unforgeable token identifying a particular stream of events.
 *
 * Every {@code Topic} instance identifies a unique topic, even if two topics share the same {@code EventType}.
 *
 * @deprecated use gov.nasa.ammos.plandev.merlin.protocol.driver.Topic instead
 */
@Deprecated()
@Capability
public final class Topic<EventType> {
    public final gov.nasa.ammos.plandev.merlin.protocol.driver.Topic<EventType> topic =
            new gov.nasa.ammos.plandev.merlin.protocol.driver.Topic<>();
}
