package gov.nasa.jpl.aerie.types;

public final class ResourceActivitySource implements ActivitySource<String> { // TODO
  String value;

  public ResourceActivitySource(String resourceName) {
    value = resourceName;
  }

  @Override
  public String getValue() {
    return value;
  }
}
