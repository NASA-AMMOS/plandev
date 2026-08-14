package gov.nasa.jpl.aerie.merlin.driver.engine;

import java.util.UUID;

/** A typed wrapper for task IDs. */
public record TaskId(String id) {
  public static TaskId generate() {
    return new TaskId(UUID.randomUUID().toString());
  }
}
