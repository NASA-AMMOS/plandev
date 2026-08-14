package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

public final class UnclaimableSimulationException extends Exception {
  public final long datasetId;

  public UnclaimableSimulationException(final long datasetId) {
    super(String.format("Unsuccessful attempt to claim simulation for dataset id `%s`", datasetId));
    this.datasetId = datasetId;
  }
}
