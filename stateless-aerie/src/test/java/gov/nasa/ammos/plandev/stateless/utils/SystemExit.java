package gov.nasa.jpl.aerie.stateless.utils;

public class SystemExit extends RuntimeException {
  private final int statusCode;

  public SystemExit(int statusCode) {
    this.statusCode = statusCode;
  }
  public int getStatusCode() { return statusCode; }
}
