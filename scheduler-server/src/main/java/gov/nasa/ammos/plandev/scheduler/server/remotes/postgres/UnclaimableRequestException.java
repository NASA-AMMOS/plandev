package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

public final class UnclaimableRequestException extends Exception {
  public final long specificationId;

  public UnclaimableRequestException(final long specificationId) {
    super(String.format("Unsuccessful attempt to claim scheduling request for specification id `%s`", specificationId));
    this.specificationId = specificationId;
  }
}
