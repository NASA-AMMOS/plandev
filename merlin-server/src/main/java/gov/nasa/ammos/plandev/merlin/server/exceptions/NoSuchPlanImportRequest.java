package gov.nasa.ammos.plandev.merlin.server.exceptions;

import java.sql.SQLException;

public class NoSuchPlanImportRequest extends SQLException {
  public NoSuchPlanImportRequest(int requestId) {
    super("No such plan import request exists: "+requestId);
  }
}
