package gov.nasa.ammos.plandev.merlin.protocol.model;

import gov.nasa.ammos.plandev.merlin.protocol.driver.Querier;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.util.Optional;

public interface Condition {
  /**
   * POSTCONDITION: The return value `x` satisfies `x.noLaterThan(atLatest)`.
   */
  Optional<Duration> nextSatisfied(Querier now, Duration atLatest);
}
