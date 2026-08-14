package gov.nasa.jpl.aerie.merlin.server.exceptions;

import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;

public class SimulationDatasetMismatchException extends Exception {
  public final SimulationDatasetId simDatasetId;
  public final PlanId planId;

  public SimulationDatasetMismatchException(final PlanId pid, final SimulationDatasetId sid) {
    super("Simulation Dataset with id `" + sid.id() + "` does not belong to Plan with id `"+pid.id()+"`");
    this.planId = pid;
    this.simDatasetId = sid;
  }
}
