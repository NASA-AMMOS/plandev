package gov.nasa.jpl.aerie.permissions.exceptions;

/**
 * Wrapper Exception for all thrown exceptions in the Permissions Service.
 * This exception contains the root exception thrown alongside the recommended
 * HTTP Status code that should be returned based on the exception.
 */
public class PermissionsException extends Exception {
  private final int httpStatus;
  private final Exception rootException;

  public PermissionsException(int httpStatus, Exception rootException) {
    super(rootException);
    this.httpStatus = httpStatus;
    this.rootException = rootException;
  }

  public int httpStatusCode() {
    return httpStatus;
  }

  public Exception rootException() { return rootException; }
}
