package gov.nasa.ammos.plandev.contrib.streamline.core;

/**
 * Alias for a Supplier.
 *
 * <p>
 *     While structurally identical to {@link gov.nasa.ammos.plandev.merlin.framework.Resource},
 *     the value returned by this interface is meant to be wrapped
 *     with additional information that should be stripped away
 *     before giving to {@link gov.nasa.ammos.plandev.merlin.framework.Registrar}.
 * </p>
 */
public interface ThinResource<A> {
  A getDynamics();
}
