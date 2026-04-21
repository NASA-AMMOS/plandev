package gov.nasa.jpl.aerie.workspace.server.types;

import gov.nasa.jpl.aerie.workspace.server.FormattedError;

import javax.json.Json;
import javax.json.JsonValue;

public sealed interface HandlerResult {
  int status();
  JsonValue jsonResponse();

  record Success(int status, String response) implements HandlerResult {
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
