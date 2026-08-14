package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

/**
 * A period of time over which a dynamics occurs.
 * @param extent The duration from the start to the end of this segment
 * @param dynamics The behavior of the resource during this segment
 * @param <Dynamics> A choice between Real and SerializedValue
 */
public record ProfileSegment<Dynamics>(Duration extent, Dynamics dynamics) {
}
