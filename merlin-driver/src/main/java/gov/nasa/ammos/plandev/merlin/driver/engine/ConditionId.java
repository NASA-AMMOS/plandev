package gov.nasa.jpl.aerie.merlin.driver.engine;

import java.util.UUID;

/** A typed wrapper for condition IDs. */
public record ConditionId(String id) {
  public static ConditionId generate() {
    return new ConditionId(UUID.randomUUID().toString());
  }
}
