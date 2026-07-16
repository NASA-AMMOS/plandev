package gov.nasa.jpl.aerie.merlin.server.exceptions;

import gov.nasa.jpl.aerie.merlin.server.models.PlanId;

public final class NoSuchPlanException extends Exception {
  public final PlanId id;

  public NoSuchPlanException(final PlanId id) {
    super("No plan exists with id `" + id.id() + "`");
    this.id = id;
  }
}
