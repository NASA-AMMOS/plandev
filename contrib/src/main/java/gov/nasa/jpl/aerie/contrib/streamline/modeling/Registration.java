package gov.nasa.jpl.aerie.contrib.streamline.modeling;

public final class Registration {
    private Registration() {}

    public static Registrar REGISTRAR;

    /**
     * Initialize the primary registrar.
     * This is called by {@link gov.nasa.jpl.aerie.contrib.streamline.StreamlineSystem#init}
     * and should not be called by the model directly.
     */
    public static void init(
            final gov.nasa.jpl.aerie.merlin.framework.Registrar baseRegistrar,
            final Registrar.ErrorBehavior errorBehavior) {
        REGISTRAR = new Registrar(baseRegistrar, errorBehavior);
    }
}
