package gov.nasa.jpl.aerie.contrib.streamline.debugging;

import gov.nasa.jpl.aerie.merlin.framework.Registrar;

public final class Logging {
    private Logging() {}

    /**
     * The "main" logger. Unless you have a compelling reason to direct logging somewhere else,
     * this logger should be used by virtually all model components.
     * This logger will be initialized automatically when a registrar is constructed.
     */
    public static Logger LOGGER;

    /**
     * Initialize the primary logger.
     * This is called by {@link gov.nasa.jpl.aerie.contrib.streamline.StreamlineSystem#init}
     * and should not be called by the model directly.
     */
    public static void init(final Registrar registrar) {
        LOGGER = new Logger(registrar);
    }
}
