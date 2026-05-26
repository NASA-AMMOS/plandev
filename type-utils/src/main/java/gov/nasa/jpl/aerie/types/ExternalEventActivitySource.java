package gov.nasa.jpl.aerie.types;

public final class ExternalEventActivitySource implements ActivitySource<ExternalEvent> { // TODO, make External Event somehow
  ExternalEvent value;

  public ExternalEventActivitySource(ExternalEvent resourceName) {
    value = resourceName;
  }

  @Override
  public ExternalEvent getValue() {
    return value;
  }
}
