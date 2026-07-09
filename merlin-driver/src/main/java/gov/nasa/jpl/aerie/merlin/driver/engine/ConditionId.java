package gov.nasa.jpl.aerie.merlin.driver.engine;

import java.util.UUID;

/** A typed wrapper for condition IDs. */
public record ConditionId(String id, TaskId sourceTask) {
  public static ConditionId generate(final TaskId sourceTask) {
    return new ConditionId(UUID.randomUUID().toString(), sourceTask);
  }
}
