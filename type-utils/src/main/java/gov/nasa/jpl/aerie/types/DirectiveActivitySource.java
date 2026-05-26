package gov.nasa.jpl.aerie.types;

public final class DirectiveActivitySource implements ActivitySource<ActivityDirective> {
  ActivityDirective value;

  public DirectiveActivitySource(ActivityDirective resourceName) {
    value = resourceName;
  }

  @Override
  public ActivityDirective getValue() {
    return value;
  }
}
