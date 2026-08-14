package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

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
