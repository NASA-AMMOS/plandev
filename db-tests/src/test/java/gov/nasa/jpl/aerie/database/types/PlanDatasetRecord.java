package gov.nasa.jpl.aerie.database.types;

import gov.nasa.jpl.aerie.database.MerlinDatabaseTestHelper;

import java.sql.SQLException;

public record PlanDatasetRecord(int planId, int datasetId, String startOffset) {
  /**
   * Refetches this dataset's information from the database and returns it as a new PlanDatasetRecord.
   * Does NOT update this object.
   */
  public PlanDatasetRecord refresh(MerlinDatabaseTestHelper merlinHelper) throws SQLException {
    return merlinHelper.getPlanDataset(this.planId, this.datasetId);
  }
}
