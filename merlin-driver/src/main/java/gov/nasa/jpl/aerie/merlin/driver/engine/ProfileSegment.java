package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

/**
 * A period of time over which a dynamics occurs.
 * @param extent The duration from the start to the end of this segment
 * @param dynamics The behavior of the resource during this segment
 * @param <Dynamics> A choice between Real and SerializedValue
 */
public record ProfileSegment<Dynamics>(Duration extent, Dynamics dynamics) implements Comparable<ProfileSegment<?>> {
  /**
   * Orders by extent and then dynamics, using string comparison as last resort if dynamics isn't Comparable.
   * @param o the object to be compared.
   * @return a negative integer if this &lt; o, 0 if this == o, else a positive integer
   */
  @Override
  public int compareTo(final ProfileSegment<?> o) {
    int c = this.extent.compareTo(o.extent);
    if (c != 0) return c;
    final var td = this.dynamics;
    final var od = o.dynamics;
    if (td instanceof Comparable cd) return cd.compareTo(od);
    if (td.equals(od)) return 0;
    if (!td.getClass().equals(od.getClass())) {
      c = td.getClass().toString().compareTo(od.getClass().toString());
      if (c != 0) return c;
    }
    return td.toString().compareTo(od.toString());
  }
}
