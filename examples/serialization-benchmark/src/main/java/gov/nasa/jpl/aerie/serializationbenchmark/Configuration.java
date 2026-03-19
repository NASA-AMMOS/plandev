package gov.nasa.jpl.aerie.serializationbenchmark;

public record Configuration(
    int initialResourceCount,
    double updateFrequency
) {
  public Configuration() {
    this(100, 1.0);
  }
}
