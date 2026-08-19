package gov.nasa.ammos.plandev.scheduler;

import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

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
