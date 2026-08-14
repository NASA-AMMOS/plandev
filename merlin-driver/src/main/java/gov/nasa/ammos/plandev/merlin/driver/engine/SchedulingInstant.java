package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

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
