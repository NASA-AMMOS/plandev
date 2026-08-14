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
     * This is called when constructing a {@link gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar},
     * and does not need to be called directly by the model.
     */
    public static void init(final Registrar registrar) {
        if (LOGGER == null) {
            LOGGER = new Logger(registrar);
        } else {
            LOGGER.warning("Attempting to re-initialize primary logger. This attempt is being ignored.");
        }
    }
}
