package gov.nasa.jpl.aerie.scheduler.goals;

/**
 * In the case of the temporal context changing (i.e. the {@code Expression<Windows>} passed to a Goal
 * is reevaluated and the resulting Windows are now different from before), throw this exception
 * as this is presently unexpected behavior! May want to reduce this to a warning, but the user
 * should definitely know.
 */
public final class UnexpectedTemporalContextChangeException extends RuntimeException {
  public UnexpectedTemporalContextChangeException(final String message) {
    super(message);
  }
}
