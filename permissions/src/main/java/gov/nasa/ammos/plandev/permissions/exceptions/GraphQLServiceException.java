package gov.nasa.ammos.plandev.permissions.exceptions;

import javax.json.JsonValue;

public class GraphQLServiceException extends Exception {
  public final JsonValue errors;
  public GraphQLServiceException(final String message, final JsonValue errors) {
    super(message);
    this.errors = errors;
  }
}
