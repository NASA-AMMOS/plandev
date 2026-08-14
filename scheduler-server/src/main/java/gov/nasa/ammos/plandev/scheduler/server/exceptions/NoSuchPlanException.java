package gov.nasa.jpl.aerie.scheduler.server.exceptions;

import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;

public class NoSuchPlanException extends Exception {
  private final PlanId id;

  public NoSuchPlanException(final PlanId id) {
    super("No plan exists with id `" + id.id() + "`");
    this.id = id;
  }

  public PlanId getInvalidPlanId() {
    return this.id;
  }
}
