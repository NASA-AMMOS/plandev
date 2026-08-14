package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

/**
 * Collection of standard dimensions, including all SI base dimensions.
 */
public final class StandardDimensions {
  private StandardDimensions() {}

  // SI base dimensions:
  public static final Dimension TIME = Dimension.createBase("Time");
  public static final Dimension LENGTH = Dimension.createBase("Length");
  public static final Dimension MASS = Dimension.createBase("Mass");
  public static final Dimension CURRENT = Dimension.createBase("Current");
  public static final Dimension TEMPERATURE = Dimension.createBase("Temperature");
  public static final Dimension LUMINOUS_INTENSITY = Dimension.createBase("Luminous Intensity");
  public static final Dimension AMOUNT = Dimension.createBase("Amount of Substance");

  // Additional base dimensions we've found useful in practice
  public static final Dimension INFORMATION = Dimension.createBase("Information");
  public static final Dimension ANGLE = Dimension.createBase("Angle");
}
