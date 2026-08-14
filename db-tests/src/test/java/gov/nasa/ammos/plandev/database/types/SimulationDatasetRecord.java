package gov.nasa.jpl.aerie.database.types;

import gov.nasa.jpl.aerie.database.MerlinDatabaseTestHelper;

import java.sql.SQLException;

public record SimulationDatasetRecord(int simDatasetId, int datasetId, String startOffset) {
  public SimulationDatasetRecord refresh(MerlinDatabaseTestHelper merlinHelper) throws SQLException {
    return merlinHelper.getSimulationDataset(this.simDatasetId);
  }
}
