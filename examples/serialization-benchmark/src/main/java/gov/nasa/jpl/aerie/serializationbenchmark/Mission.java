package gov.nasa.jpl.aerie.serializationbenchmark;

import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.EnumValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.serializationbenchmark.DataTypes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mission model with multiple resources that require heavy serialization.
 */
public final class Mission {

  public final Register<SystemState> systemState;
  public final Register<Attitude> attitude;
  public final Register<Ephemeris> ephemeris;
  public final Register<ComplexActivityState> activityState;
  public final Register<NestedData> nestedData;
  public final Register<LargeRecord> largeRecord;
  public final Register<List<TelemetryPoint>> telemetryBuffer;
  public final Register<Map<String, Vector3D>> vectorMap;

  public Mission(final Registrar registrar, final Configuration config) {
    // Initialize with complex default values
    this.systemState = Register.forImmutable(new SystemState(
        SystemMode.IDLE,
        100.0,
        25.0,
        List.of("Power", "Thermal", "Comms"),
        Map.of("Power", 0.95, "Thermal", 0.88, "Comms", 0.92),
        new Attitude(
            new Quaternion(1.0, 0.0, 0.0, 0.0),
            new Vector3D(0.0, 0.0, 0.0)
        )
    ));

    this.attitude = Register.forImmutable(new Attitude(
        new Quaternion(1.0, 0.0, 0.0, 0.0),
        new Vector3D(0.0, 0.0, 0.0)
    ));

    this.ephemeris = Register.forImmutable(new Ephemeris(
        0L,
        new Vector3D(0.0, 0.0, 0.0),
        new Vector3D(0.0, 0.0, 0.0),
        "J2000"
    ));

    this.activityState = Register.forImmutable(new ComplexActivityState(
        "none",
        "idle",
        List.of(),
        Map.of(),
        List.of(),
        new Attitude(new Quaternion(1.0, 0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0)),
        new Ephemeris(0L, new Vector3D(0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0), "J2000")
    ));

    this.nestedData = Register.forImmutable(new NestedData(
        Map.of(),
        List.of(),
        Map.of()
    ));

    this.largeRecord = Register.forImmutable(new LargeRecord(
        "field1", "field2", "field3", "field4", "field5",
        1.0, 2.0, 3.0, 4.0, 5.0,
        100L, 200L, 300L,
        true, false,
        List.of("a", "b", "c"),
        List.of(1.0, 2.0, 3.0),
        Map.of("key1", "value1", "key2", "value2"),
        new Vector3D(0.0, 0.0, 0.0),
        new Quaternion(1.0, 0.0, 0.0, 0.0)
    ));

    this.telemetryBuffer = Register.forImmutable(new ArrayList<>());

    this.vectorMap = Register.forImmutable(new HashMap<>());

    // Register all resources with appropriate mappers
    registrar.discrete("/systemState", this.systemState, createSystemStateMapper());
    registrar.discrete("/attitude", this.attitude, createAttitudeMapper());
    registrar.discrete("/ephemeris", this.ephemeris, createEphemerisMapper());
    registrar.discrete("/activityState", this.activityState, createComplexActivityStateMapper());
    registrar.discrete("/nestedData", this.nestedData, createNestedDataMapper());
    registrar.discrete("/largeRecord", this.largeRecord, createLargeRecordMapper());
  }

  // Helper methods to create ValueMappers for complex types
  private static RecordValueMapper<Vector3D> createVector3DMapper() {
    return new RecordValueMapper<>(
        Vector3D.class,
        List.of(
            new RecordValueMapper.Component<>("x", Vector3D::x, new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("y", Vector3D::y, new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("z", Vector3D::z, new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())
        )
    );
  }

  private static RecordValueMapper<Quaternion> createQuaternionMapper() {
    return new RecordValueMapper<>(
        Quaternion.class,
        List.of(
            new RecordValueMapper.Component<>("w", Quaternion::w,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("x", Quaternion::x,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("y", Quaternion::y,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("z", Quaternion::z,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())
        )
    );
  }

  private static RecordValueMapper<Attitude> createAttitudeMapper() {
    return new RecordValueMapper<>(
        Attitude.class,
        List.of(
            new RecordValueMapper.Component<>("quaternion", Attitude::quaternion, createQuaternionMapper()),
            new RecordValueMapper.Component<>("angularVelocity", Attitude::angularVelocity, createVector3DMapper())
        )
    );
  }

  private static RecordValueMapper<Ephemeris> createEphemerisMapper() {
    return new RecordValueMapper<>(
        Ephemeris.class,
        List.of(
            new RecordValueMapper.Component<>("timestamp", Ephemeris::timestamp,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("position", Ephemeris::position, createVector3DMapper()),
            new RecordValueMapper.Component<>("velocity", Ephemeris::velocity, createVector3DMapper()),
            new RecordValueMapper.Component<>("frame", Ephemeris::frame,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())
        )
    );
  }

  private static RecordValueMapper<TelemetryPoint> createTelemetryPointMapper() {
    return new RecordValueMapper<>(
        TelemetryPoint.class,
        List.of(
            new RecordValueMapper.Component<>("timestamp", TelemetryPoint::timestamp,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("channel", TelemetryPoint::channel,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("value", TelemetryPoint::value,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("unit", TelemetryPoint::unit,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("status", TelemetryPoint::status,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())
        )
    );
  }

  private static RecordValueMapper<ComplexActivityState> createComplexActivityStateMapper() {
    return new RecordValueMapper<>(
        ComplexActivityState.class,
        List.of(
            new RecordValueMapper.Component<>("activityId", ComplexActivityState::activityId,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("status", ComplexActivityState::status,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("arguments", ComplexActivityState::arguments,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("metrics", ComplexActivityState::metrics,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), 
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())),
            new RecordValueMapper.Component<>("telemetry", ComplexActivityState::telemetry,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(createTelemetryPointMapper())),
            new RecordValueMapper.Component<>("attitude", ComplexActivityState::attitude, createAttitudeMapper()),
            new RecordValueMapper.Component<>("ephemeris", ComplexActivityState::ephemeris, createEphemerisMapper())
        )
    );
  }

  private static RecordValueMapper<NestedData> createNestedDataMapper() {
    return new RecordValueMapper<>(
        NestedData.class,
        List.of(
            new RecordValueMapper.Component<>("vectorMaps", NestedData::vectorMaps,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), 
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(createVector3DMapper()))),
            new RecordValueMapper.Component<>("quaternionLists", NestedData::quaternionLists,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), createQuaternionMapper()))),
            new RecordValueMapper.Component<>("nestedMaps", NestedData::nestedMaps,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), 
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), 
                        new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())))
        )
    );
  }

  private static RecordValueMapper<SystemState> createSystemStateMapper() {
    return new RecordValueMapper<>(
        SystemState.class,
        List.of(
            new RecordValueMapper.Component<>("mode", SystemState::mode, new EnumValueMapper<>(SystemMode.class)),
            new RecordValueMapper.Component<>("powerLevel", SystemState::powerLevel,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("thermalLevel", SystemState::thermalLevel,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("activeSubsystems", SystemState::activeSubsystems,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("subsystemHealth", SystemState::subsystemHealth,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), 
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())),
            new RecordValueMapper.Component<>("currentAttitude", SystemState::currentAttitude, createAttitudeMapper())
        )
    );
  }

  private static RecordValueMapper<LargeRecord> createLargeRecordMapper() {
    return new RecordValueMapper<>(
        LargeRecord.class,
        List.of(
            new RecordValueMapper.Component<>("field1", LargeRecord::field1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field2", LargeRecord::field2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field3", LargeRecord::field3,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field4", LargeRecord::field4,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("field5", LargeRecord::field5,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper()),
            new RecordValueMapper.Component<>("value1", LargeRecord::value1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value2", LargeRecord::value2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value3", LargeRecord::value3,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value4", LargeRecord::value4,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("value5", LargeRecord::value5,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper()),
            new RecordValueMapper.Component<>("timestamp1", LargeRecord::timestamp1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("timestamp2", LargeRecord::timestamp2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("timestamp3", LargeRecord::timestamp3,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.LongValueMapper()),
            new RecordValueMapper.Component<>("flag1", LargeRecord::flag1,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper()),
            new RecordValueMapper.Component<>("flag2", LargeRecord::flag2,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper()),
            new RecordValueMapper.Component<>("stringList", LargeRecord::stringList,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("doubleList", LargeRecord::doubleList,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.ListValueMapper<>(
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper())),
            new RecordValueMapper.Component<>("stringMap", LargeRecord::stringMap,
                new gov.nasa.jpl.aerie.contrib.serialization.mappers.MapValueMapper<>(new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper(), 
                    new gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper())),
            new RecordValueMapper.Component<>("vector", LargeRecord::vector, createVector3DMapper()),
            new RecordValueMapper.Component<>("quaternion", LargeRecord::quaternion, createQuaternionMapper())
        )
    );
  }
}
