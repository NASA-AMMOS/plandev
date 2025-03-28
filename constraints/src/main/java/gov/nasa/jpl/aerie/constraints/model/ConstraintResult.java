package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;

import javax.json.JsonObject;
import java.util.List;

import static gov.nasa.jpl.aerie.constraints.json.ConstraintParsers.constraintResultP;

public sealed interface ConstraintResult {
  record Uncached(
      List<Violation> violations,
      List<Interval> gaps,
      List<String> resourceIds,
      Long constraintId,
      Long constraintRevision,
      String constraintName
  ) implements ConstraintResult {
    @Override
    public JsonObject toJSON() {
      return constraintResultP.unparse(this).asJsonObject();
    }
  }

  /**
   * As Cached Constraint Results are not meant to be created by parsing JSON,
   * this record returns the results object as it was fetched from the database.
   *
   * @param resultId The database id of the constraint result
   * @param results The JsonObject stored in the database, as it was stored.
   *    Unparsed, as this is only needed for the action's return.
   */
  record Cached(long resultId, JsonObject results) implements ConstraintResult {
    @Override
    public JsonObject toJSON() {
      return results;
    }
  }

  /**
   * Send the ConstraintResult to a JSON Object, for the purpose of storing the result
   * in the Database or returning it as part of the Run Constraints Action
   * @return A JSON representation of the results object.
   */
  JsonObject toJSON();
}
