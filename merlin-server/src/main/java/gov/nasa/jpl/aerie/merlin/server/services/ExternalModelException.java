package gov.nasa.jpl.aerie.merlin.server.services;

/**
 * A simulation that failed for a reason specific to an EXTERNAL model backend, carrying a message
 * meant for the operator.
 *
 * <p>These used to be plain {@code RuntimeException}s. {@link SimulationAgent} classifies the exception
 * types it knows and lets everything else fall to the worker's catch-all, which reports
 * {@code UNEXPECTED_SIMULATION_EXCEPTION} / "Something went wrong while simulating" and buries the real
 * text in the stack trace. So every external failure -- identity drift, an ingest-gate rejection, an
 * unsupported anchor -- surfaced identically and uselessly, and precisely the messages written to tell
 * an operator what to do next were the ones nobody could see.
 *
 * <p>Each of these failures is actionable, and the action differs by {@link Kind}, which is carried
 * separately so a client can branch on it rather than parse prose.
 */
public final class ExternalModelException extends RuntimeException {
  public enum Kind {
    /** The backend no longer serves the model this row was registered against. Re-introspect. */
    IDENTITY_DRIFT,
    /** Returned results contradict the registered model. Fix the backend, or re-introspect if it changed. */
    INGEST_GATE,
    /** The plan uses something the external contract cannot express, e.g. an end-anchored directive. */
    UNSUPPORTED_PLAN,
    /** The backend could not be reached, or answered with an error. */
    BACKEND_UNAVAILABLE,
    /** The user canceled while results were streaming. */
    CANCELED,
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
