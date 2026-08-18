package gov.nasa.ammos.plandev.merlin.protocol.driver;

import gov.nasa.ammos.plandev.merlin.protocol.Capability;

/**
 * An unforgeable token identifying a particular stream of events.
 *
 * Every {@code Topic} instance identifies a unique topic, even if two topics share the same {@code EventType}.
 */
@Capability
public final class Topic<EventType> {
  @Override
  public String toString() {
    return "@" + System.identityHashCode(this);
  }
}
