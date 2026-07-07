package protocol.model;

import protocol.driver.Querier;
import protocol.types.Duration;

import java.util.Optional;

public interface Condition {
  /**
   * POSTCONDITION: The return value `x` satisfies `x.noLaterThan(atLatest)`.
   */
  Optional<Duration> nextSatisfied(Querier now, Duration atLatest);
}
