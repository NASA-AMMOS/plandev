package gov.nasa.jpl.aerie.contrib.streamline.core;

/**
 * Alias for a Supplier.
 *
 * <p>
 *     While structurally identical to {@link gov.nasa.jpl.aerie.merlin.framework.Resource},
 *     the value returned by this interface is meant to be wrapped
 *     with additional information that should be stripped away
 *     before giving to {@link gov.nasa.jpl.aerie.merlin.framework.Registrar}.
 * </p>
 */
public interface ThinResource<A> {
  A getDynamics();
}
