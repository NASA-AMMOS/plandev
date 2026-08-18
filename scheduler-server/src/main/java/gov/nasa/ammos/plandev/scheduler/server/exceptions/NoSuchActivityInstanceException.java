package gov.nasa.ammos.plandev.scheduler.server.exceptions;

import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

public class NoSuchActivityInstanceException extends Exception {
  private final ActivityDirectiveId id;

  public NoSuchActivityInstanceException(final ActivityDirectiveId id) {
    super("No activity instance exists with id `" + id + "`");
    this.id = id;
  }

  public ActivityDirectiveId getInvalidPlanId() {
    return this.id;
  }
}
