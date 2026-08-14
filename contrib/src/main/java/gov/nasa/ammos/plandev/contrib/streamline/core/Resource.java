package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling;

/**
 * A function returning a fully-wrapped dynamics,
 * and the primary way models track state and report results.
 */
public interface Resource<D> extends ThinResource<ErrorCatching<Expiring<D>>> {
    /**
     * Turn on profiling for all resources derived through {@link ResourceMonad}
     * or created by {@link MutableResource#resource}.
     *
     * <p>
     *     Calling this method once before constructing your model will profile virtually every resource.
     *     Profiling may be compute and/or memory intensive, and should not be used in production.
     * </p>
     * <p>
     *     If only a few resources are suspect, you can also call {@link Profiling#profile}
     *     directly on just those resource, rather than profiling every resource.
     * </p>
     * <p>
     *     Call {@link Profiling#dump()} to see results.
     * </p>
     */
    static void profileAllResources() {
        ResourceMonad.profileAllResources();
        MutableResource.profileAllResources();
    }
}
