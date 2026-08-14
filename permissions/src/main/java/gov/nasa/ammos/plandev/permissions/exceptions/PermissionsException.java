package gov.nasa.ammos.plandev.permissions.exceptions;

import gov.nasa.ammos.plandev.json.FormattedError;

/**
 * Wrapper Exception for all thrown exceptions in the Permissions Service.
 * This exception contains the root exception thrown alongside the recommended
 * HTTP Status code that should be returned based on the exception.
 */
public class PermissionsException extends Exception {
  private final int httpStatus;
  private final FormattedError formattedError;

  public PermissionsException(int httpStatus, FormattedError formattedError) {
    this.httpStatus = httpStatus;
    this.formattedError = formattedError;
  }

  public int httpStatusCode() {
    return httpStatus;
  }
  public FormattedError formattedError() {return formattedError;}
}
