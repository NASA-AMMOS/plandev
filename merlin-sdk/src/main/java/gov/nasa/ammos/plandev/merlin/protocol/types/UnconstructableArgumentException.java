package gov.nasa.jpl.aerie.merlin.protocol.types;

public final class UnconstructableArgumentException extends Exception {
  public final String parameterName, failure;

  public UnconstructableArgumentException(final String parameterName, final String failure) {
    super("%s: %s".formatted(parameterName, failure));
    this.parameterName = parameterName;
    this.failure = failure;
  }
}
