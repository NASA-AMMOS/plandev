package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

public final class NoSuchSimulationDatasetException extends Exception {
  public final long datasetId;

  public NoSuchSimulationDatasetException(final long datasetId) {
    super(String.format("No simulation dataset with dataset id `%s` exists", datasetId));
    this.datasetId = datasetId;
  }
}
