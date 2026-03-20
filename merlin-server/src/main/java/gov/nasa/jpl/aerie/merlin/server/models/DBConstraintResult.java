package gov.nasa.jpl.aerie.merlin.server.models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;

/**
 * A constraint result that is stored in the database
 * @param id The database id of the constraint result
 * @param results The ObjectNode stored in the database, as it was stored.
 *    Unparsed, as this is only needed for the action's return.
 */
public record DBConstraintResult(
    long id,
    ObjectNode results
) implements ConstraintResult {
  /**
   * As DB Constraint Results are not meant to be created by parsing JSON,
   * this record returns the results object as it was fetched from the database.
   */
  @Override
  public ObjectNode toJSON() {
    return results;
  }
}
