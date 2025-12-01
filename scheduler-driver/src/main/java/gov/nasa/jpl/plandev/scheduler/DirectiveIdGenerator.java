package gov.nasa.jpl.plandev.scheduler;

import gov.nasa.jpl.plandev.types.ActivityDirectiveId;

public class DirectiveIdGenerator {
  private long counter;

  public DirectiveIdGenerator(long startFrom) {
    this.counter = startFrom;
  }

  public ActivityDirectiveId next() {
    final var result = counter;
    counter += 1;
    return new ActivityDirectiveId(result);
  }
}
