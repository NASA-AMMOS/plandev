package gov.nasa.ammos.plandev.merlin.server.services;

/**
 * A failure specific to a mission model that has no JAR, carrying a message meant to be read.
 *
 * <p>Its own type, rather than a plain {@code RuntimeException}, because of where these end up.
 * {@link SimulationAgent} classifies the exception types it knows and lets everything else fall to the
 * worker's catch-all, which reports {@code UNEXPECTED_SIMULATION_EXCEPTION} / "Something went wrong
 * while simulating" and buries the real text in the stack trace. Javalin does the same thing over HTTP:
 * an unrecognized exception becomes a bare 500. So the messages written specifically to tell somebody
 * what to do next were the ones nobody could see.
 *
 * <p>Each of these failures is actionable and the action differs by {@link Kind}, which is carried
 * separately so a client can branch on it rather than parse prose.
 */
public final class ExternalModelException extends RuntimeException {
  public enum Kind {
    /**
     * Recorded results contradict the model they were declared against. The findings name what
     * disagreed; fix the producer, or import a run declared against the types this model actually has.
     */
    INGEST_GATE,
    /**
     * The model cannot be simulated at all -- it has no JAR and nothing behind it to run. Not an error
     * condition so much as a property of the model, and the message is the model's own explanation.
     */
    SIMULATION_UNSUPPORTED,
  }

  public final Kind kind;

  public ExternalModelException(final Kind kind, final String message) {
    super(message);
    this.kind = kind;
  }

  public ExternalModelException(final Kind kind, final String message, final Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }
}
