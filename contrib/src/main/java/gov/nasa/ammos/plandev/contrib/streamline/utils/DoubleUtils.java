package gov.nasa.jpl.aerie.contrib.streamline.utils;

public final class DoubleUtils {
  private DoubleUtils() {}

  /**
   * Relative error tolerance for fuzzy equality on doubles.
   */
  public static final double FUZZY_EQUALITY_TOLERANCE = 1e-14;

  public static boolean areEqualResults(double original, double x, double y) {
    return Math.abs(x - y) <= Math.max(Math.abs(original), Math.max(Math.abs(x), Math.abs(y))) * FUZZY_EQUALITY_TOLERANCE;
  }
}
