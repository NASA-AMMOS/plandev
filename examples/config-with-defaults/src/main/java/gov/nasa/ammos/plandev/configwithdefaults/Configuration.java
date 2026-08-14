package gov.nasa.ammos.plandev.configwithdefaults;

import static gov.nasa.ammos.plandev.merlin.framework.annotations.Export.WithDefaults;

public record Configuration(Integer a, Double b, String c) {

  public static @WithDefaults final class Defaults {
    public static Integer a = 42;
    public static Double b = 3.14;
    public static String c = "JPL";
  }
}
