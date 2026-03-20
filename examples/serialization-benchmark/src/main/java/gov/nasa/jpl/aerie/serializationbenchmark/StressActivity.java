package gov.nasa.jpl.aerie.serializationbenchmark;

import gov.nasa.jpl.aerie.merlin.framework.ModelActions;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.serializationbenchmark.DataTypes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An activity that modifies many resources simultaneously to stress-test serialization.
 * Each instance updates all indexed resources, generating many resource profile segments.
 */
@ActivityType("StressActivity")
public record StressActivity(int index, double magnitude) {

  @EffectModel
  public void run(final Mission model) {
    // Update system state
    model.systemState.set(new SystemState(
        SystemMode.ACTIVE,
        50.0 + magnitude,
        30.0 + index,
        List.of("Power", "Thermal", "Comms", "GNC", "Payload_" + index),
        Map.of("Power", 0.9 + magnitude * 0.001,
               "Thermal", 0.8 + magnitude * 0.002,
               "Comms", 0.95),
        new Attitude(
            new Quaternion(Math.cos(index * 0.01), Math.sin(index * 0.01), 0.0, 0.0),
            new Vector3D(0.01 * index, 0.02 * index, 0.03 * index)
        )
    ));

    // Update attitude
    model.attitude.set(new Attitude(
        new Quaternion(
            Math.cos(magnitude * 0.1),
            Math.sin(magnitude * 0.1),
            Math.cos(magnitude * 0.2),
            Math.sin(magnitude * 0.2)
        ),
        new Vector3D(magnitude * 0.01, magnitude * 0.02, magnitude * 0.03)
    ));

    // Update ephemeris
    model.ephemeris.set(new Ephemeris(
        1000L * index,
        new Vector3D(1000.0 * index, 2000.0 * index, 3000.0 * index),
        new Vector3D(7.5 + magnitude, 8.5 + magnitude, 9.5 + magnitude),
        "J2000"
    ));

    // Build telemetry data
    var telemetry = new ArrayList<TelemetryPoint>();
    for (int i = 0; i < 5; i++) {
      telemetry.add(new TelemetryPoint(
          1000L * index + i,
          "channel_" + i,
          magnitude * (i + 1),
          "units",
          "OK"
      ));
    }

    // Update complex activity state
    model.activityState.set(new ComplexActivityState(
        "activity_" + index,
        "executing",
        List.of("arg1", "arg2", "arg3_" + index),
        Map.of("metric1", magnitude, "metric2", magnitude * 2),
        telemetry,
        new Attitude(
            new Quaternion(0.707, 0.707, 0.0, 0.0),
            new Vector3D(0.1 * index, 0.2 * index, 0.3 * index)
        ),
        new Ephemeris(1000L * index,
                      new Vector3D(100.0 * index, 200.0 * index, 300.0 * index),
                      new Vector3D(1.0, 2.0, 3.0), "ECLIPJ2000")
    ));

    // Update large record
    model.largeRecord.set(new LargeRecord(
        "stress_" + index + "_1",
        "stress_" + index + "_2",
        "stress_" + index + "_3",
        "stress_" + index + "_4",
        "stress_" + index + "_5",
        magnitude, magnitude * 2, magnitude * 3, magnitude * 4, magnitude * 5,
        1000L * index, 2000L * index, 3000L * index,
        index % 2 == 0, index % 3 == 0,
        List.of("item_" + index, "item_" + (index + 1), "item_" + (index + 2)),
        List.of(magnitude, magnitude * 1.1, magnitude * 1.2),
        Map.of("k1_" + index, "v1", "k2_" + index, "v2"),
        new Vector3D(index * 10.0, index * 20.0, index * 30.0),
        new Quaternion(0.5, 0.5, 0.5, 0.5)
    ));

    // Update indexed resources
    for (int i = 0; i < Mission.NUM_INDEXED_RESOURCES; i++) {
      model.indexedCounters[i].set(magnitude + i + index);
    }

    // Simulate some duration
    ModelActions.delay(Duration.of(1, Duration.MINUTE));

    // Set back to idle
    model.systemState.set(new SystemState(
        SystemMode.IDLE,
        100.0,
        25.0,
        List.of("Power", "Thermal", "Comms"),
        Map.of("Power", 0.95, "Thermal", 0.88, "Comms", 0.92),
        new Attitude(new Quaternion(1.0, 0.0, 0.0, 0.0), new Vector3D(0.0, 0.0, 0.0))
    ));
  }
}
