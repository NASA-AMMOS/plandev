package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import javax.json.JsonObject;

/**
 * A constraint result that is stored in the database
 * @param id The database id of the constraint result
 * @param results The JsonObject stored in the database, as it was stored.
 *    Unparsed, as this is only needed for the action's return.
 */
public record DBConstraintResult(
    long id,
    JsonObject results
) implements ConstraintResult {
  /**
   * As DB Constraint Results are not meant to be created by parsing JSON,
   * this record returns the results object as it was fetched from the database.
   */
  @Override
  public JsonObject toJSON() {
    return results;
  }
}
