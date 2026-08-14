package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import java.util.Optional;
import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;

public record SimulationStateRecord(Status status, Optional<SimulationFailure> reason) {
  public static SimulationStateRecord pending() {
    return new SimulationStateRecord(Status.PENDING, Optional.empty());
  }

  public static SimulationStateRecord incomplete(SimulationFailure reason) {
    return new SimulationStateRecord(Status.INCOMPLETE, Optional.of(reason));
  }

  public static SimulationStateRecord failed(final SimulationFailure reason) {
    return new SimulationStateRecord(Status.FAILED, Optional.of(reason));
  }

  public static SimulationStateRecord success() {
    return new SimulationStateRecord(Status.SUCCESS, Optional.empty());
  }

  public enum Status {
    PENDING("pending"),
    INCOMPLETE("incomplete"),
    FAILED("failed"),
    SUCCESS("success");

    public final String label;
    Status(final String label) {
      this.label = label;
    }

    public static Status fromString(final String label) throws InvalidSimulationStatusException {
      return switch(label) {
        case "pending" -> PENDING;
        case "incomplete" -> INCOMPLETE;
        case "failed" -> FAILED;
        case "success" -> SUCCESS;
        default -> throw new InvalidSimulationStatusException(label);
      };
    }

    public static final class InvalidSimulationStatusException extends Exception {
      public final String invalidStatus;

      public InvalidSimulationStatusException(final String invalidStatus) {
        super(String.format("Invalid Simulation Status: %s", invalidStatus));
        this.invalidStatus = invalidStatus;
      }
    }
  }
}
