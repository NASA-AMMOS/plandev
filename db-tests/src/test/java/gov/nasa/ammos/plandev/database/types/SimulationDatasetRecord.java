package gov.nasa.ammos.plandev.database.types;

import gov.nasa.ammos.plandev.database.MerlinDatabaseTestHelper;

import java.sql.SQLException;

public record SimulationDatasetRecord(int simDatasetId, int datasetId, String startOffset) {
  public SimulationDatasetRecord refresh(MerlinDatabaseTestHelper merlinHelper) throws SQLException {
    return merlinHelper.getSimulationDataset(this.simDatasetId);
  }
}
