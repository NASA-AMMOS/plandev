package gov.nasa.ammos.plandev.database.types;

import gov.nasa.ammos.plandev.database.MerlinDatabaseTestHelper;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public record Activity(
    int activityId,
    int planId,
    String name,
    int sourceSchedulingGoalId,
    int sourceSchedulingGoalInvocationId,
    String createdAt,
    String createdBy,
    String lastModifiedAt,
    String lastModifiedBy,
    String startOffset,
    String type,
    String arguments,
    String lastModifiedArgumentsAt,
    String metadata,
    String anchorId,  // Since anchor_id allows for null values, this is a String to avoid confusion over what a 0 (the default value returned by ResultSet.getInt()) means
    boolean anchoredToStart
) {

  /**
   * Assert that two Activities on different plans have the same shareable properties
   *
   * DOES NOT CHECK THE FOLLOWING PROPERTIES:
   *  - planId
   *  - lastModifiedAt
   *  - lastModifiedBy
   *  - lastModifiedArgumentsAt
   */
  public static void assertActivityEquals(final Activity expected, final Activity actual) {
    // validate all shared properties
    assertEquals(expected.activityId(), actual.activityId());
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.sourceSchedulingGoalId(), actual.sourceSchedulingGoalId());
    assertEquals(expected.sourceSchedulingGoalInvocationId(), actual.sourceSchedulingGoalInvocationId());
    assertEquals(expected.createdAt(), actual.createdAt());
    assertEquals(expected.createdBy(), actual.createdBy());
    assertEquals(expected.startOffset(), actual.startOffset());
    assertEquals(expected.type(), actual.type());
    assertEquals(expected.arguments(), actual.arguments());
    assertEquals(expected.metadata(), actual.metadata());
    assertEquals(expected.anchorId(), actual.anchorId());
    assertEquals(expected.anchoredToStart(), actual.anchoredToStart());
  }

  /**
   * Refetches this activity's information from the database and returns it as a new Activity.
   * Does NOT update this object.
   */
  public Activity refresh(MerlinDatabaseTestHelper merlinHelper) throws SQLException {
    return merlinHelper.getActivity(this.planId, this.activityId);
  }
}
