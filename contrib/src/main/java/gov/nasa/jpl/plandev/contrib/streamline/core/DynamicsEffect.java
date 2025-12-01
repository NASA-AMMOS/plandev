package gov.nasa.jpl.plandev.contrib.streamline.core;

/**
 * General interface for an effect applied to a {@link MutableResource}
 */
public interface DynamicsEffect<D extends Dynamics<?, D>> {
    ErrorCatching<Expiring<D>> apply(ErrorCatching<Expiring<D>> dynamics);
}
