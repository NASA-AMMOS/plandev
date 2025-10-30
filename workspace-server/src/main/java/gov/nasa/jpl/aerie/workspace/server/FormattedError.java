package gov.nasa.jpl.aerie.workspace.server;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.jpl.aerie.permissions.exceptions.Forbidden;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.validation.ValidationException;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Class for formatting exceptions thrown during in Workspaces into JSON objects
 * that meet the Aerie HTTP endpoint error message format
 * Relevant ticket going over said format: https://github.com/NASA-AMMOS/aerie/issues/1732
 */
@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
final class FormattedError {
  private final String type;
  private final String message;
  private final String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
  private final static String service = "aerie_workspace";
  private Optional<String> cause = Optional.empty();
  private Optional<String> trace = Optional.empty();
  private Optional<JsonObject> data = Optional.empty();

  /**
   * For use in the event of an endpoint failing without throwing an exception.
   * i.e. Workspace's delete file endpoint failing because java.nio.File#deleteFile returned "false"
   */
  public FormattedError(String message) {
    this.type = "INTERNAL_ERROR";
    this.message = message;
  }

  /**
   * For use in the event of an endpoint failing without throwing an exception, but where there's a more detailed cause.
   */
  public FormattedError(String message, String cause) {
    this.type = "INTERNAL_ERROR";
    this.message = message;
    this.cause = Optional.ofNullable(cause);
  }

  /**
   * Create a FormattedException from a generic Exception object.
   * @param type the category of exception. Should be in SCREAMING_SNAKE_CASE
   * @param ex the exception to be formatted.
   */
  public FormattedError(String type, Exception ex) {
    this.type = type;
    message = ex.getMessage() == null ? "No exception message provided." : ex.getMessage();
    trace = Optional.of(generateTrace(ex));
  }

  /**
   * Create a FormattedException from a generic Exception object with a custom error message.
   * The exception's built-in error message, if included, will be put into the 'cause' field.
   * @param type the category of exception. Should be in SCREAMING_SNAKE_CASE
   * @param message the custom error message explaining the cause of the error.
   *  Should be human-readable and between 1-2 sentences.
   * @param ex the exception to be formatted.
   */
  public FormattedError(String type, String message, Exception ex) {
    this.type = type;
    this.message = message;
    cause = Optional.ofNullable(ex.getMessage());
    trace = Optional.of(generateTrace(ex));
  }

  // region Constructors for specific exceptions
  //  This helps `type` be consistent every time the exception is thrown.
  // NoSuchWorkspace
  public FormattedError(NoSuchWorkspaceException nse) {
    this("NO_SUCH_WORKSPACE", nse);
  }
  public FormattedError(NoSuchWorkspaceException nse, String message) {
    this("NO_SUCH_WORKSPACE", message, nse);
  }

  public FormattedError(gov.nasa.jpl.aerie.permissions.exceptions.NoSuchWorkspaceException nse, String message) {
    this("NO_SUCH_WORKSPACE", message, nse);
  }

  // IOException
  public FormattedError(IOException nse) {
    this("IO_EXCEPTION", nse);
  }
  public FormattedError(IOException nse, String message) {
    this("IO_EXCEPTION", message, nse);
  }

  // SQLException
  public FormattedError(SQLException se) {
    this("SQL_EXCEPTION", se);
  }
  public FormattedError(SQLException se, String message) {
    this("SQL_EXCEPTION", message, se);
  }

  // WorkspaceFileOpException
  public FormattedError(WorkspaceFileOpException wfe, String message) {
    this("FILE_OPERATION_EXCEPTION", message, wfe);
  }

  // Forbidden
  public FormattedError(Forbidden ue) {
    this("FORBIDDEN", ue);
  }

  // Unauthorized
  public FormattedError(UnauthorizedResponse ue) {
    this.type = "UNAUTHORIZED";
    this.message = ue.getMessage() != null ? ue.getMessage() : "Unauthorized";
    // Include additional details, if present
    if(!ue.getDetails().isEmpty()) {
      final var dataBuilder = Json.createObjectBuilder();
      ue.getDetails().forEach(dataBuilder::add);
      this.data = Optional.of(dataBuilder.build());
    }
  }

  // PermissionsServiceException
  public FormattedError(PermissionsServiceException pse, String message) {
    this("PERMISSIONS_SERVICE_EXCEPTION", message, pse);
  }

  // NumberFormatException
  public FormattedError(NumberFormatException nfe) {
    this("NUMBER_PARSING_EXCEPTION", nfe);
  }

  // IllegalArgumentException
  public FormattedError(IllegalArgumentException iae) {
    this("ILLEGAL_ARGUMENT", iae);
  }

  // JSONException
  public FormattedError(JsonException je, String message){
    this("JSON_PARSING_EXCEPTION", message, je);
  }

  // ValidationException
  public FormattedError(ValidationException ve) {
    this.type = "ENDPOINT_VALIDATION_EXCEPTION";
    this.message = ve.getMessage() != null ? ve.getMessage() : "Invalid request";
    trace = Optional.of(generateTrace(ve));
  }

  // Null Pointer Exception
  public FormattedError(NullPointerException ne, String message) {
    this("NULL_POINTER_EXCEPTION", message, ne);
  }
  //endregion

  /**
   * Generate a stack trace string from an Exception.
   */
  private String generateTrace(Exception ex) {
    final var sw = new StringWriter();
    try(final var pw = new PrintWriter(sw)) {
      ex.printStackTrace(pw);
    }
    return sw.toString();
  }

  /**
   * Export this object to a JsonObject.
   */
  public JsonObject toJson() {
    // Include all mandatory fields
    final var builder = Json.createObjectBuilder()
                            .add("type", type)
                            .add("message", message)
                            .add("timestamp", timestamp)
                            .add("service", service); // Not mandatory on spec, but always known

    // Include optional fields, if present
    cause.ifPresent((c) -> builder.add("cause", c));
    trace.ifPresent((t) -> builder.add("trace", t));
    data.ifPresent((d) -> builder.add("data", d));

    return builder.build();
  }

  /**
   * Internal class so that Javalin serializes the FormattedError class using its `toJson` method.
   * This avoids needing to call `toJson` every time the FormattedError class is used as an endpoint return.
   * The class implements Jackson's JsonSerializer in specific because Javalin uses Jackson as its default JSON Mapper.
   */
  static final class FormattedErrorSerializer extends JsonSerializer<FormattedError> {
    @Override
    public void serialize(
        final FormattedError formattedError,
        final JsonGenerator jsonGenerator,
        final SerializerProvider serializerProvider) throws IOException
    {
      jsonGenerator.writeRaw(formattedError.toJson().toString());
    }
  }
}
