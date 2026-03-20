package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
public record GoalInvocationId(int goalId, int invocationId) {
  public static GoalInvocationId fromJSON(ObjectNode json) {
    return new GoalInvocationId(
        json.get("goal_id").intValue(),
        json.get("goal_invocation_id").intValue()
    );
  }
}
