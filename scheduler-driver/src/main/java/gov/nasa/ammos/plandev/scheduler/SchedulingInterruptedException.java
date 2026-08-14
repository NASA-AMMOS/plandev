package gov.nasa.jpl.aerie.scheduler;

public class SchedulingInterruptedException extends InterruptedException {
  public final String location;

  /**
   * Create a SchedulingInterruptedException that is aware of the part of the
   * scheduling process in which it was created
   * @param location Where in scheduling this Exception was thrown
   */
  public SchedulingInterruptedException(String location) {
    super("Scheduling was interrupted while "+ location);
    this.location = location;
  }
}
