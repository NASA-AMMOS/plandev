package gov.nasa.jpl.plandev.merlin.driver.engine;

import java.util.UUID;

/** A typed wrapper for span IDs. */
public record SpanId(String id) {
  public static SpanId generate() {
    return new SpanId(UUID.randomUUID().toString());
  }
}
