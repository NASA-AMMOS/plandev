package gov.nasa.jpl.aerie.merlin.server.exceptions;

import java.util.Collection;

public class InvalidSimulationDatasetException extends Exception {
  public final Collection<String> invalidActivityTypes;

  public InvalidSimulationDatasetException(final Collection<String> invalidActivityTypes) {
    super("Simulation dataset contains activity types not present in the plan's mission model: " + invalidActivityTypes);
    this.invalidActivityTypes = invalidActivityTypes;
  }
}
