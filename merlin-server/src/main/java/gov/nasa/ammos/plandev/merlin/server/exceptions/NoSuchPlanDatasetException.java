package gov.nasa.jpl.aerie.merlin.server.exceptions;

import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;

public final class NoSuchPlanDatasetException extends Exception {
  public final DatasetId id;

  public NoSuchPlanDatasetException(final DatasetId id) {
    super("No plan dataset exists with id `" + id.id() + "`");
    this.id = id;
  }
}
