package gov.nasa.ammos.plandev.merlin.driver.engine;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

public record SchedulingInstant(Duration offsetFromStart, SubInstant priority)
    implements Comparable<SchedulingInstant>
{
  public Duration project() {
    return this.offsetFromStart;
  }

  @Override
  public int compareTo(final SchedulingInstant o) {
    final var x = this.offsetFromStart.compareTo(o.offsetFromStart);
    if (x != 0) return x;
    return this.priority.compareTo(o.priority);
  }
}
