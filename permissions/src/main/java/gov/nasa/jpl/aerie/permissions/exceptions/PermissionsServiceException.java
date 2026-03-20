package gov.nasa.jpl.aerie.permissions.exceptions;

import com.fasterxml.jackson.databind.JsonNode;

public class PermissionsServiceException extends Exception {
  public final JsonNode errors;
  public PermissionsServiceException(final String message, final JsonNode errors) {
    super(message);
    this.errors = errors;
  }
}
