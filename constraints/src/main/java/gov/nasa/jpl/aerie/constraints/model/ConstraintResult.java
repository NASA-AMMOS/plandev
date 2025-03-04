package gov.nasa.jpl.aerie.constraints.model;

import javax.json.JsonObject;

public interface ConstraintResult {
  /**
   * Send the ConstraintResult to a JSON Object, for the purpose of storing the result
   * in the Database or returning it as part of the Run Constraints Action
   * @return A JSON representation of the results object.
   */
  JsonObject toJSON();
}
