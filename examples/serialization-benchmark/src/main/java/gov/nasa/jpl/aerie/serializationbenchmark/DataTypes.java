package gov.nasa.jpl.aerie.serializationbenchmark;

import java.util.List;
import java.util.Map;

/**
 * Complex data structures designed to stress-test serialization performance.
 */
public final class DataTypes {

  public record Vector3D(double x, double y, double z) {}

  public record Quaternion(double w, double x, double y, double z) {}

  public record Attitude(Quaternion quaternion, Vector3D angularVelocity) {}

  public record Ephemeris(
      long timestamp,
      Vector3D position,
      Vector3D velocity,
      String frame
  ) {}

  public record TelemetryPoint(
      long timestamp,
      String channel,
      double value,
      String unit,
      String status
  ) {}

  public record ComplexActivityState(
      String activityId,
      String status,
      List<String> arguments,
      Map<String, Double> metrics,
      List<TelemetryPoint> telemetry,
      Attitude attitude,
      Ephemeris ephemeris
  ) {}

  public record NestedData(
      Map<String, List<Vector3D>> vectorMaps,
      List<Map<String, Quaternion>> quaternionLists,
      Map<String, Map<String, Double>> nestedMaps
  ) {}

  public record LargeRecord(
      String field1,
      String field2,
      String field3,
      String field4,
      String field5,
      double value1,
      double value2,
      double value3,
      double value4,
      double value5,
      long timestamp1,
      long timestamp2,
      long timestamp3,
      boolean flag1,
      boolean flag2,
      List<String> stringList,
      List<Double> doubleList,
      Map<String, String> stringMap,
      Vector3D vector,
      Quaternion quaternion
  ) {}

  public enum SystemMode {
    IDLE,
    ACTIVE,
    SAFE_MODE,
    DIAGNOSTIC,
    MAINTENANCE
  }

  public record SystemState(
      SystemMode mode,
      double powerLevel,
      double thermalLevel,
      List<String> activeSubsystems,
      Map<String, Double> subsystemHealth,
      Attitude currentAttitude
  ) {}
}
