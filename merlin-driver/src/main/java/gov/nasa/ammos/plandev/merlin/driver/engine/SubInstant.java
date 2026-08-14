package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

/*package-local*/ enum SubInstant implements Comparable<SubInstant> {
  /** Conditions must be checked first, as they may cause tasks to be scheduled. */
  Conditions,
  /** Tasks must be performed second, as they may affect resources. */
  Tasks,
  /** Resources must be gathered last. */
  Resources;

  public SchedulingInstant at(final Duration offsetFromStart) {
    return new SchedulingInstant(offsetFromStart, this);
  }
}
