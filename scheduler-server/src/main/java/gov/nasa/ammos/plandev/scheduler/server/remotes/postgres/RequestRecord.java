package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import java.util.Optional;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleFailure;

public record RequestRecord(
    long specificationId,
    long analysisId,
    long specificationRevision,
    long planRevision,
    Status status,
    Optional<ScheduleFailure> reason,
    boolean canceled,
    Optional<Long> datasetId
) {
  public enum Status {
    PENDING("pending"),
    INCOMPLETE("incomplete"),
    FAILED("failed"),
    SUCCESS("success");

    public final String label;
    Status(final String label) {
      this.label = label;
    }

    public static Status fromString(final String label) throws InvalidRequestStatusException {
      return switch (label) {
        case "pending" -> PENDING;
        case "incomplete" -> INCOMPLETE;
        case "failed" -> FAILED;
        case "success" -> SUCCESS;
        default -> throw new InvalidRequestStatusException(label);
      };
    }

    public static final class InvalidRequestStatusException extends Exception {
      public final String invalidStatus;

      public InvalidRequestStatusException(final String invalidStatus) {
        super(String.format("Invalid Request Status: %s", invalidStatus));
        this.invalidStatus = invalidStatus;
      }
    }
  }
}
