package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedModelType;
import gov.nasa.jpl.aerie.merlin.driver.*;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;

import javax.json.Json;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;

public class SimulateMapSchedule {
  public static void main(final String[] args) {
    simulateWithMapSchedule();
  }

  private static MissionModel<Mission>
  makeMissionModel(final MissionModelBuilder builder, final Instant planStart, final Configuration config) {
    final var factory = new GeneratedModelType();
    final var registry = DirectiveTypeRegistry.extract(factory);
    final var model = factory.instantiate(planStart, config, builder);
    return builder.build(model, registry);
  }

  private static
  void simulateWithMapSchedule() {
    final var config = new Configuration();
    final var startTime = Instant.now();
    final var simulationDuration = duration(25, SECONDS);
    final MissionModel<Mission> missionModel = makeMissionModel(new MissionModelBuilder(), Instant.EPOCH, config);

    final var schedule = loadSchedule();
    final var simulationResults = SimulationDriver.simulate(
        missionModel,
        schedule,
        startTime,
        simulationDuration,
        startTime,
        simulationDuration,
        () -> false);

      simulationResults.getRealProfiles().forEach((name, samples) -> {
        System.out.println(name + ":");
        samples.segments().forEach(point -> System.out.format("\t%s\t%s\n", point.extent(), point.dynamics()));
      });

      simulationResults.getDiscreteProfiles().forEach((name, samples) -> {
        System.out.println(name + ":");
        samples.segments().forEach(point -> System.out.format("\t%s\t%s\n", point.extent(), point.dynamics()));
      });

    simulationResults.getSimulatedActivities().forEach((name, activity) -> {
      System.out.println(name + ": " + activity.start() + " for " + activity.duration());
    });
  }

  private static Map<ActivityDirectiveId, ActivityDirective> loadSchedule() {
    final var schedule = new HashMap<ActivityDirectiveId, ActivityDirective>();
    long counter = 0;

    final var planJson = Json.createReader(SimulateMapSchedule.class.getResourceAsStream("plan.json")).readValue();
    for (final var scheduledActivity : planJson.asJsonArray()) {
      final var deferInMicroseconds = scheduledActivity.asJsonObject().getJsonNumber("defer").longValueExact();
      final var activityType = scheduledActivity.asJsonObject().getString("type");

      final var arguments = new HashMap<String, SerializedValue>();
      for (final var field : scheduledActivity.asJsonObject().getJsonObject("arguments").entrySet()) {
        arguments.put(field.getKey(), JsonEncoding.decode(field.getValue()));
      }

      schedule.put(
          new ActivityDirectiveId(counter++),
          new ActivityDirective(
              duration(deferInMicroseconds, MICROSECONDS),
              new SerializedActivity(activityType, arguments),
              null,
              true));
    }

    return schedule;
  }
}
