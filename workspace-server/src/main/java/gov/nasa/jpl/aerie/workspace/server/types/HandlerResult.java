package gov.nasa.jpl.aerie.workspace.server.types;

import gov.nasa.jpl.aerie.workspace.server.FormattedError;

import javax.json.Json;
import javax.json.JsonValue;

public sealed interface HandlerResult {
  int status();
  JsonValue jsonResponse();

  record Success(int status, String response, String etag) implements HandlerResult {
    /** For successes with no ETag (directories, moves, copies, deletes). */
    public Success(int status, String response) {
      this(status, response, null);
    }
    @Override
    public JsonValue jsonResponse() {
      return Json.createValue(response);
    }
  }
  record Failure(int status, FormattedError error) implements HandlerResult {
    @Override
    public JsonValue jsonResponse() {
      return error.toJson();
    }
  }
}
