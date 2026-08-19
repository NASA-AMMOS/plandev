package gov.nasa.ammos.plandev.merlin.server.exceptions;

import gov.nasa.ammos.plandev.merlin.server.models.DatasetId;

public final class NoSuchPlanDatasetException extends Exception {
  public final DatasetId id;

  public NoSuchPlanDatasetException(final DatasetId id) {
    super("No plan dataset exists with id `" + id.id() + "`");
    this.id = id;
  }
}
