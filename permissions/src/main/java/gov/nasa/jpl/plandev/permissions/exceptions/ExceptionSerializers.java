package gov.nasa.jpl.plandev.permissions.exceptions;

import javax.json.Json;
import javax.json.JsonValue;
import java.io.IOException;


public class ExceptionSerializers {
  public static JsonValue serializeNoSuchPlanException(final NoSuchPlanException ex) {
    return Json.createObjectBuilder()
               .add("message", "no such plan")
               .add("plan_id", ex.id.id())
               .build();
  }

  public static JsonValue serializeNoSuchSchedulingSpecificationException(final NoSuchSchedulingSpecificationException ex) {
    return Json.createObjectBuilder()
               .add("message", "no such scheduling specification")
               .add("plan_id", ex.id.id())
               .build();
  }

  public static JsonValue serializePermissionsServiceException(final PermissionsServiceException ex) {
    return Json.createObjectBuilder()
               .add("message", "error in response")
               .add("errors", ex.errors)
               .build();
  }

  public static JsonValue serializeForbiddenException(final Forbidden ex) {
    return Json.createObjectBuilder().add("message", ex.getMessage()).build();
  }

  public static JsonValue serializeIOException(final IOException ex) {
    return Json.createObjectBuilder()
               .add("message", "error fetching permissions data")
               .add("cause", ex.getMessage())
               .build();
  }
}
