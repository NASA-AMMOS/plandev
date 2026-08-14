package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

/**
 * A single segment of a resource profile;
 * a value which evolves as time passes.
 */
public interface Dynamics<V, D extends Dynamics<V, D>> {
    /**
     * Get the current value.
     */
    V extract();

    /**
     * Evolve for the given time.
     *
     * @apiNote This method should always return the same value when called on the same object with the same duration
     */
    D step(Duration t);
}
