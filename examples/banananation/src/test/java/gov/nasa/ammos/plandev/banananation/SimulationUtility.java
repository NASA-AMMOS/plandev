package gov.nasa.ammos.plandev.banananation;

import gov.nasa.ammos.plandev.banananation.generated.GeneratedModelType;
import gov.nasa.ammos.plandev.merlin.driver.*;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.ActivityDirective;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import gov.nasa.ammos.plandev.types.Plan;
import gov.nasa.ammos.plandev.types.SerializedActivity;
import gov.nasa.ammos.plandev.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public final class SimulationUtility {
  public static SimulationResults
  simulate(final Map<ActivityDirectiveId, ActivityDirective> schedule, final Duration simulationDuration) {
    final var dataPath = Path.of(SimulationUtility.class.getResource("data/lorem_ipsum.txt").getPath());
    final var config = new Configuration(Configuration.DEFAULT_PLANT_COUNT, Configuration.DEFAULT_PRODUCER, dataPath, Configuration.DEFAULT_INITIAL_CONDITIONS);
    final var startTime = Instant.now();
    final var missionModel = gov.nasa.ammos.plandev.orchestration.simulation.SimulationUtility.instantiateMissionModel(
        new GeneratedModelType(),
        Instant.EPOCH,
        config);

    final var plan = new Plan(
        "plan",
        new Timestamp(startTime),
        new Timestamp(startTime.plus(simulationDuration.in(Duration.MICROSECOND), ChronoUnit.MICROS)),
        schedule,
        Map.of("initialDataPath", SerializedValue.of(dataPath.toString())));

    try(final var simUtil = new gov.nasa.ammos.plandev.orchestration.simulation.SimulationUtility()) {
      return simUtil.simulate(missionModel, plan).get();
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @SafeVarargs
  public static Map<ActivityDirectiveId, ActivityDirective> buildSchedule(final Pair<Duration, SerializedActivity>... activitySpecs) {
    final var schedule = new HashMap<ActivityDirectiveId, ActivityDirective>();
    long counter = 0;

    for (final var activitySpec : activitySpecs) {
      schedule.put(
          new ActivityDirectiveId(counter++),
          new ActivityDirective(activitySpec.getLeft(), activitySpec.getRight(), null, true));
    }

    return schedule;
  }
}
