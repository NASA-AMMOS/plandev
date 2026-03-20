package gov.nasa.jpl.aerie.serializationbenchmark;

import gov.nasa.jpl.aerie.contrib.models.Accumulator;
import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.EnumValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.serializationbenchmark.DataTypes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stress-test mission model with hundreds of resources for serialization benchmarking.
 *
 * Resources:
 *  - 6 complex discrete resources (structs, lists, maps, nested records)
 *  - 200 indexed discrete counter resources (doubles)
 *  - 100 real (linear) resources
 *  = 306 total resources
 */
public final class Mission {

  /** Number of simple indexed discrete resources. */
  public static final int NUM_INDEXED_RESOURCES = 200;
  /** Number of real (linear) resources. */
  public static final int NUM_REAL_RESOURCES = 100;

  // Complex discrete resources
  public final Register<SystemState> systemState;
  public final Register<Attitude> attitude;
  public final Register<Ephemeris> ephemeris;
  public final Register<ComplexActivityState> activityState;
  public final Register<NestedData> nestedData;
  public final Register<LargeRecord> largeRecord;

  // 200 indexed discrete resources (double counters)
  public final Register<Double>[] indexedCounters;

  // 100 real (linear) resources
  public final Accumulator[] realCounters;

  @SuppressWarnings("unchecked")
  public Mission(final Registrar registrar, final Configuration config) {
    // Complex discrete resources
    this.systemState = Register.forImmutable(new SystemState(
        SystemMode.IDLE, 100.0, 25.0,
        List.of("Power", "Thermal", "Comms"),
        Map.of("Power", 0.95, "Thermal", 0.88, "Comms", 0.92),
        new Attitude(new Quaternion(1.0, 0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0))
    ));
    this.attitude = Register.forImmutable(new Attitude(
        new Quaternion(1.0, 0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0)
    ));
    this.ephemeris = Register.forImmutable(new Ephemeris(
        0L, new Vector3D(0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0), "J2000"
    ));
    this.activityState = Register.forImmutable(new ComplexActivityState(
        "none", "idle", List.of(), Map.of(), List.of(),
        new Attitude(new Quaternion(1.0, 0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0)),
        new Ephemeris(0L, new Vector3D(0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0), "J2000")
    ));
    this.nestedData = Register.forImmutable(new NestedData(Map.of(), List.of(), Map.of()));
    this.largeRecord = Register.forImmutable(new LargeRecord(
        "f1", "f2", "f3", "f4", "f5",
        1.0, 2.0, 3.0, 4.0, 5.0, 100L, 200L, 300L, true, false,
        List.of("a", "b", "c"), List.of(1.0, 2.0, 3.0),
        Map.of("k1", "v1", "k2", "v2"),
        new Vector3D(0.0, 0.0, 0.0), new Quaternion(1.0, 0.0, 0.0, 0.0)
    ));

    registrar.discrete("/systemState", this.systemState, createSystemStateMapper());
    registrar.discrete("/attitude", this.attitude, createAttitudeMapper());
    registrar.discrete("/ephemeris", this.ephemeris, createEphemerisMapper());
    registrar.discrete("/activityState", this.activityState, createComplexActivityStateMapper());
    registrar.discrete("/nestedData", this.nestedData, createNestedDataMapper());
    registrar.discrete("/largeRecord", this.largeRecord, createLargeRecordMapper());

    // 200 indexed discrete resources
    this.indexedCounters = new Register[NUM_INDEXED_RESOURCES];
    final var doubleMapper = new DoubleValueMapper();
    for (int i = 0; i < NUM_INDEXED_RESOURCES; i++) {
      this.indexedCounters[i] = Register.forImmutable(0.0);
      registrar.discrete("/counter_" + i, this.indexedCounters[i], doubleMapper);
    }

    // 100 real (linear) resources
    this.realCounters = new Accumulator[NUM_REAL_RESOURCES];
    for (int i = 0; i < NUM_REAL_RESOURCES; i++) {
      this.realCounters[i] = new Accumulator(0.0, 0.0);
      registrar.real("/real_" + i, this.realCounters[i]);
    }
  }

  // ValueMapper factories (unchanged from original)
  static RecordValueMapper<Vector3D> createVector3DMapper() {
    return new RecordValueMapper<>(Vector3D.class, List.of(
        new RecordValueMapper.Component<>("x", Vector3D::x, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("y", Vector3D::y, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("z", Vector3D::z, new DoubleValueMapper())
    ));
  }

  private static RecordValueMapper<Quaternion> createQuaternionMapper() {
    return new RecordValueMapper<>(Quaternion.class, List.of(
        new RecordValueMapper.Component<>("w", Quaternion::w, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("x", Quaternion::x, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("y", Quaternion::y, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("z", Quaternion::z, new DoubleValueMapper())
    ));
  }

  static RecordValueMapper<Attitude> createAttitudeMapper() {
    return new RecordValueMapper<>(Attitude.class, List.of(
        new RecordValueMapper.Component<>("quaternion", Attitude::quaternion, createQuaternionMapper()),
        new RecordValueMapper.Component<>("angularVelocity", Attitude::angularVelocity, createVector3DMapper())
    ));
  }

  private static RecordValueMapper<Ephemeris> createEphemerisMapper() {
    return new RecordValueMapper<>(Ephemeris.class, List.of(
        new RecordValueMapper.Component<>("timestamp", Ephemeris::timestamp, new LongValueMapper()),
        new RecordValueMapper.Component<>("position", Ephemeris::position, createVector3DMapper()),
        new RecordValueMapper.Component<>("velocity", Ephemeris::velocity, createVector3DMapper()),
        new RecordValueMapper.Component<>("frame", Ephemeris::frame, new StringValueMapper())
    ));
  }

  private static RecordValueMapper<TelemetryPoint> createTelemetryPointMapper() {
    return new RecordValueMapper<>(TelemetryPoint.class, List.of(
        new RecordValueMapper.Component<>("timestamp", TelemetryPoint::timestamp, new LongValueMapper()),
        new RecordValueMapper.Component<>("channel", TelemetryPoint::channel, new StringValueMapper()),
        new RecordValueMapper.Component<>("value", TelemetryPoint::value, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("unit", TelemetryPoint::unit, new StringValueMapper()),
        new RecordValueMapper.Component<>("status", TelemetryPoint::status, new StringValueMapper())
    ));
  }

  private static RecordValueMapper<ComplexActivityState> createComplexActivityStateMapper() {
    return new RecordValueMapper<>(ComplexActivityState.class, List.of(
        new RecordValueMapper.Component<>("activityId", ComplexActivityState::activityId, new StringValueMapper()),
        new RecordValueMapper.Component<>("status", ComplexActivityState::status, new StringValueMapper()),
        new RecordValueMapper.Component<>("arguments", ComplexActivityState::arguments, new ListValueMapper<>(new StringValueMapper())),
        new RecordValueMapper.Component<>("metrics", ComplexActivityState::metrics, new MapValueMapper<>(new StringValueMapper(), new DoubleValueMapper())),
        new RecordValueMapper.Component<>("telemetry", ComplexActivityState::telemetry, new ListValueMapper<>(createTelemetryPointMapper())),
        new RecordValueMapper.Component<>("attitude", ComplexActivityState::attitude, createAttitudeMapper()),
        new RecordValueMapper.Component<>("ephemeris", ComplexActivityState::ephemeris, createEphemerisMapper())
    ));
  }

  private static RecordValueMapper<NestedData> createNestedDataMapper() {
    return new RecordValueMapper<>(NestedData.class, List.of(
        new RecordValueMapper.Component<>("vectorMaps", NestedData::vectorMaps, new MapValueMapper<>(new StringValueMapper(), new ListValueMapper<>(createVector3DMapper()))),
        new RecordValueMapper.Component<>("quaternionLists", NestedData::quaternionLists, new ListValueMapper<>(new MapValueMapper<>(new StringValueMapper(), createQuaternionMapper()))),
        new RecordValueMapper.Component<>("nestedMaps", NestedData::nestedMaps, new MapValueMapper<>(new StringValueMapper(), new MapValueMapper<>(new StringValueMapper(), new DoubleValueMapper())))
    ));
  }

  private static RecordValueMapper<SystemState> createSystemStateMapper() {
    return new RecordValueMapper<>(SystemState.class, List.of(
        new RecordValueMapper.Component<>("mode", SystemState::mode, new EnumValueMapper<>(SystemMode.class)),
        new RecordValueMapper.Component<>("powerLevel", SystemState::powerLevel, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("thermalLevel", SystemState::thermalLevel, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("activeSubsystems", SystemState::activeSubsystems, new ListValueMapper<>(new StringValueMapper())),
        new RecordValueMapper.Component<>("subsystemHealth", SystemState::subsystemHealth, new MapValueMapper<>(new StringValueMapper(), new DoubleValueMapper())),
        new RecordValueMapper.Component<>("currentAttitude", SystemState::currentAttitude, createAttitudeMapper())
    ));
  }

  private static RecordValueMapper<LargeRecord> createLargeRecordMapper() {
    return new RecordValueMapper<>(LargeRecord.class, List.of(
        new RecordValueMapper.Component<>("field1", LargeRecord::field1, new StringValueMapper()),
        new RecordValueMapper.Component<>("field2", LargeRecord::field2, new StringValueMapper()),
        new RecordValueMapper.Component<>("field3", LargeRecord::field3, new StringValueMapper()),
        new RecordValueMapper.Component<>("field4", LargeRecord::field4, new StringValueMapper()),
        new RecordValueMapper.Component<>("field5", LargeRecord::field5, new StringValueMapper()),
        new RecordValueMapper.Component<>("value1", LargeRecord::value1, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("value2", LargeRecord::value2, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("value3", LargeRecord::value3, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("value4", LargeRecord::value4, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("value5", LargeRecord::value5, new DoubleValueMapper()),
        new RecordValueMapper.Component<>("timestamp1", LargeRecord::timestamp1, new LongValueMapper()),
        new RecordValueMapper.Component<>("timestamp2", LargeRecord::timestamp2, new LongValueMapper()),
        new RecordValueMapper.Component<>("timestamp3", LargeRecord::timestamp3, new LongValueMapper()),
        new RecordValueMapper.Component<>("flag1", LargeRecord::flag1, new BooleanValueMapper()),
        new RecordValueMapper.Component<>("flag2", LargeRecord::flag2, new BooleanValueMapper()),
        new RecordValueMapper.Component<>("stringList", LargeRecord::stringList, new ListValueMapper<>(new StringValueMapper())),
        new RecordValueMapper.Component<>("doubleList", LargeRecord::doubleList, new ListValueMapper<>(new DoubleValueMapper())),
        new RecordValueMapper.Component<>("stringMap", LargeRecord::stringMap, new MapValueMapper<>(new StringValueMapper(), new StringValueMapper())),
        new RecordValueMapper.Component<>("vector", LargeRecord::vector, createVector3DMapper()),
        new RecordValueMapper.Component<>("quaternion", LargeRecord::quaternion, createQuaternionMapper())
    ));
  }
}
