package gov.nasa.jpl.aerie.foomissionmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nasa.jpl.aerie.foomissionmodel.generated.GeneratedModelType;
import gov.nasa.jpl.aerie.merlin.driver.*;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.SerializedActivity;

import java.io.IOException;
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
    final var missionModel = makeMissionModel(new MissionModelBuilder(), Instant.EPOCH, config);

    final var schedule = loadSchedule();
    final var simulationResults = SimulationDriver.simulate(
        missionModel,
        schedule,
        startTime,
        simulationDuration,
        startTime,
        simulationDuration,
        () -> false);

      simulationResults.realProfiles.forEach((name, samples) -> {
        System.out.println(name + ":");
        samples.segments().forEach(point -> System.out.format("\t%s\t%s\n", point.extent(), point.dynamics()));
      });

      simulationResults.discreteProfiles.forEach((name, samples) -> {
        System.out.println(name + ":");
        samples.segments().forEach(point -> System.out.format("\t%s\t%s\n", point.extent(), point.dynamics()));
      });

    simulationResults.simulatedActivities.forEach((name, activity) -> {
      System.out.println(name + ": " + activity.start() + " for " + activity.duration());
    });
  }

  private static Map<ActivityDirectiveId, ActivityDirective> loadSchedule() {
    final var schedule = new HashMap<ActivityDirectiveId, ActivityDirective>();
    long counter = 0;

    final JsonNode planJson;
    try {
      planJson = new ObjectMapper().readTree(SimulateMapSchedule.class.getResourceAsStream("plan.json"));
    } catch (IOException e) {
      throw new RuntimeException("Failed to read plan.json", e);
    }
    for (final var scheduledActivity : planJson) {
      final var deferInMicroseconds = scheduledActivity.get("defer").longValue();
      final var activityType = scheduledActivity.get("type").textValue();

      final var arguments = new HashMap<String, SerializedValue>();
      final var argsNode = scheduledActivity.get("arguments");
      final var fields = argsNode.fields();
      while (fields.hasNext()) {
        final var field = fields.next();
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
