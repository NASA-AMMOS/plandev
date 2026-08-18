package gov.nasa.ammos.plandev.e2e.types.workspaces;

import javax.json.JsonArray;
import javax.json.JsonObject;

public class HasuraRequestFailure extends RuntimeException {
  private final JsonObject responseObject;

  public HasuraRequestFailure(JsonArray errors)
  {
    super(errors.toString());
    responseObject = errors.getJsonObject(0);
  }

  public JsonObject getResponse() {
    return responseObject;
  }

  @Override
  public String getMessage() {
    return responseObject.getString("message");
  }

  public JsonObject getExtensions() {
    return responseObject.getJsonObject("extensions");
  }
}
