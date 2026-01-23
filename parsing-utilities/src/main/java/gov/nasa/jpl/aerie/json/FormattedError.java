package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
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
 * Class for formatting exceptions thrown into JSON objects that meet the Aerie HTTP endpoint error message format
 * Relevant ticket going over said format: https://github.com/NASA-AMMOS/plandev/issues/1732
 */
@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
public class FormattedError {
  public enum AerieService {
    MERLIN_SERVER("aerie_merlin"),
    SCHEDULER_SERVER("aerie_scheduler"),
    WORKSPACE_SERVER("aerie_workspace"),
    PERMISSIONS_SERVICE("aerie_permissions");

    private final String serviceName;
    AerieService(String serviceName) { this.serviceName = serviceName; }
    String serviceName() {return serviceName;}
  }

  private final String type;
  private final String message;
  private final String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
  private final AerieService service;
  private Optional<String> cause = Optional.empty();
  private Optional<String> trace = Optional.empty();
  private Optional<JsonObject> data = Optional.empty();

  /**
   * For use in the event of an endpoint failing without throwing an exception.
   * i.e. Workspace's delete file endpoint failing because java.nio.File#deleteFile returned "false"
   */
  public FormattedError(AerieService service, String message) {
    this.type = "INTERNAL_ERROR";
    this.message = message;
    this.service = service;
  }

  /**
   * For use in the event of an endpoint failing without throwing an exception, but where there's a more detailed cause.
   */
  public FormattedError(AerieService service, String message, String cause) {
    this.type = "INTERNAL_ERROR";
    this.message = message;
    this.service = service;
    this.cause = Optional.ofNullable(cause);
  }

  /**
   * For use in the event of an endpoint failing without throwing an exception,
   *  but "INTERNAL_ERROR" does not make sense as the error type (i.e. the request is malformed)
   */
  public FormattedError(AerieService service, String type, String message, Optional<String> cause) {
    this.type = type;
    this.message = message;
    this.service = service;
    this.cause = cause;
  }

  /**
   * Create a FormattedException from a generic Exception object.
   * @param type the category of exception. Should be in SCREAMING_SNAKE_CASE
   * @param ex the exception to be formatted.
   */
  public FormattedError(AerieService service, String type, Exception ex) {
    this.type = type;
    message = ex.getMessage() == null ? "No exception message provided." : ex.getMessage();
    this.service = service;
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
  public FormattedError(AerieService service, String type, String message, Exception ex) {
    this.type = type;
    this.message = message;
    this.service = service;
    cause = Optional.ofNullable(ex.getMessage());
    trace = Optional.of(generateTrace(ex));
  }

  // region Constructors for specific exceptions
  //  This helps `type` be consistent every time the exception is thrown.

  // IOException
  public FormattedError(AerieService service, IOException nse) {
    this(service, "IO_EXCEPTION", nse);
  }
  public FormattedError(AerieService service, IOException nse, String message) {
    this(service, message, nse);
  }

  // SQLException
  public FormattedError(AerieService service, SQLException se) {
    this(service, "SQL_EXCEPTION", se);
  }
  public FormattedError(AerieService service, SQLException se, String message) {
    this(service, "SQL_EXCEPTION", message, se);
  }

  // Unauthorized
  public FormattedError(AerieService service, UnauthorizedResponse ue) {
    this.service = service;
    this.type = "UNAUTHORIZED";
    this.message = ue.getMessage() != null ? ue.getMessage() : "Unauthorized";
    // Include additional details, if present
    if(!ue.getDetails().isEmpty()) {
      final var dataBuilder = Json.createObjectBuilder();
      ue.getDetails().forEach(dataBuilder::add);
      this.data = Optional.of(dataBuilder.build());
    }
  }

  // NumberFormatException
  public FormattedError(AerieService service, NumberFormatException nfe) {
    this(service, "NUMBER_PARSING_EXCEPTION", nfe);
  }

  // IllegalArgumentException
  public FormattedError(AerieService service, IllegalArgumentException iae) {
    this(service, "ILLEGAL_ARGUMENT", iae);
  }

  // JSONException
  public FormattedError(AerieService service, JsonException je){
    this(service, "JSON_PARSING_EXCEPTION", je);
  }

  public FormattedError(AerieService service, JsonException je, String message){
    this(service, "JSON_PARSING_EXCEPTION", message, je);
  }

  // ValidationException
  public FormattedError(final AerieService service, ValidationException ve) {
    this.service = service;
    this.type = "ENDPOINT_VALIDATION_EXCEPTION";
    this.message = ve.getMessage() != null ? ve.getMessage() : "Invalid request";
    trace = Optional.of(generateTrace(ve));
  }

  // Null Pointer Exception
  public FormattedError(AerieService service, NullPointerException ne, String message) {
    this(service, "NULL_POINTER_EXCEPTION", message, ne);
  }

  // Security Exception
  public FormattedError(AerieService service, SecurityException se) {
    this(service, "SECURITY_EXCEPTION", se.getMessage(), se);
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

  public String getType() { return type; }
  public String getMessage() { return message; }

  /**
   * Export this object to a JsonObject.
   */
  public JsonObject toJson() {
    // Include all mandatory fields
    final var builder = Json.createObjectBuilder()
                            .add("type", type)
                            .add("message", message)
                            .add("timestamp", timestamp)
                            .add("service", service.serviceName()); // Not mandatory on spec, but always known

    // Include optional fields, if present
    cause.ifPresent((c) -> builder.add("cause", c));
    trace.ifPresent((t) -> builder.add("trace", t));
    data.ifPresent((d) -> builder.add("data", d));

    return builder.build();
  }

  @Override
  public String toString() {
    return this.toJson().toString();
  }

  /**
   * Internal class so that Javalin serializes the FormattedError class using its `toJson` method.
   * This avoids needing to call `toJson` every time the FormattedError class is used as an endpoint return.
   * The class implements Jackson's JsonSerializer in specific because Javalin uses Jackson as its default JSON Mapper.
   */
  public static final class FormattedErrorSerializer extends JsonSerializer<FormattedError> {
    @Override
    public void serialize(
        final FormattedError formattedError,
        final JsonGenerator jsonGenerator,
        final SerializerProvider serializerProvider) throws IOException
    {
      jsonGenerator.writeRaw(formattedError.toString());
    }
  }
}
