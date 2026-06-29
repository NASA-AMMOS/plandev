package gov.nasa.jpl.aerie.database.types;

import gov.nasa.jpl.aerie.database.MerlinDatabaseTestHelper;

import java.sql.SQLException;
import java.time.ZonedDateTime;

public record SimulationSpecification(
    int id,
    int planId,
    int revision,
    Integer templateId,
    String arguments,
    ZonedDateTime simStartTime,
    ZonedDateTime simEndTime
) {
  /**
   * Refetches this specification's information from the database and returns it as a new SimulationSpecification.
   * Does NOT update this object.
   */
  public SimulationSpecification refresh(MerlinDatabaseTestHelper merlinHelper) throws SQLException {
    return merlinHelper.getSimulationSpecification(this.planId);
  }
}
