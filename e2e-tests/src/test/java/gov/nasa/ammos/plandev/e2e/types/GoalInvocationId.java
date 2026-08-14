package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;

public record GoalInvocationId(int goalId, int invocationId) {
  public static GoalInvocationId fromJSON(JsonObject json) {
    return new GoalInvocationId(
        json.getInt("goal_id"),
        json.getInt("goal_invocation_id")
    );
  }
}
