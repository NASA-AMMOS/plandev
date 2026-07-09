package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SubInstantDuration;

public interface EventSource {
  Cursor cursor();

  void freeze(SubInstantDuration time);
  SubInstantDuration timeFroze();
  default boolean isFrozen() {
    return timeFroze() != null;
  }

  interface Cursor {
    <State> Cell<State> stepUp(Cell<State> cell);
  }
}
