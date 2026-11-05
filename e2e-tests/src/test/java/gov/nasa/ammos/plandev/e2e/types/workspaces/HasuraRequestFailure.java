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

  // For Hasura Action failures, server errors will be contained in the "extensions" object
  public JsonObject getActionError() {
    return responseObject.getJsonObject("extensions");
  }

  // For Hasura Mutation and Function failures, database errors will be nested inside
  // deep inside the "extensions" field
  public JsonObject getDatabaseError() {
    return getActionError().getJsonObject("internal").getJsonObject("error");
  }
}
