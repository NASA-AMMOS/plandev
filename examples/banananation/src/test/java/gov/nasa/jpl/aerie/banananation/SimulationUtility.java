package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.banananation.generated.GeneratedModelType;
import gov.nasa.jpl.aerie.merlin.driver.*;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

public final class SimulationUtility {


  public static <Model> SimulationDriver<Model>
  getDriver(final Duration simulationDuration)
  {
    return getDriver(simulationDuration, false);
  }

  public static <Model> SimulationDriver<Model>
  getDriver(final Duration simulationDuration, boolean runDaemons)
  {
    final var dataPath = Path.of(SimulationUtility.class.getResource("data/lorem_ipsum.txt").getPath());
    final var config = new Configuration(
        Configuration.DEFAULT_PLANT_COUNT,
        Configuration.DEFAULT_PRODUCER,
        dataPath,
        Configuration.DEFAULT_INITIAL_CONDITIONS,
        runDaemons);
    final var simStartTime = Instant.EPOCH;
    final var missionModel = gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility.instantiateMissionModel(
        new GeneratedModelType(),
        simStartTime,
        config);

    var driver = new SimulationDriver(
        missionModel, simStartTime, simulationDuration);
    return driver;
  }

  public static SimulationResultsInterface
  simulate(final Map<ActivityDirectiveId, ActivityDirective> schedule, final Duration simulationDuration) {
    return simulate(schedule, simulationDuration, false);
  }

  public static SimulationResultsInterface
  simulate(final Map<ActivityDirectiveId, ActivityDirective> schedule, final Duration simulationDuration, boolean runDaemons) {
    final var dataPath = Path.of(SimulationUtility.class.getResource("data/lorem_ipsum.txt").getPath());
    final var config = new Configuration(Configuration.DEFAULT_PLANT_COUNT, Configuration.DEFAULT_PRODUCER, dataPath, Configuration.DEFAULT_INITIAL_CONDITIONS, runDaemons);
    final var startTime = Instant.now();
    final var missionModel = gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility.instantiateMissionModel(
        new GeneratedModelType(),
        Instant.EPOCH,
        config);

    final var plan = new Plan(
        "plan",
        new Timestamp(startTime),
        new Timestamp(startTime.plus(simulationDuration.in(Duration.MICROSECOND), ChronoUnit.MICROS)),
        schedule,
        Map.of("initialDataPath", SerializedValue.of(dataPath.toString())));

    try(final var simUtil = new gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility()) {
      return simUtil.simulate(missionModel, plan).get();
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private static long _counter = 0;

  @SafeVarargs
  public static Map<ActivityDirectiveId, ActivityDirective> buildSchedule(final Pair<Duration, SerializedActivity>... activitySpecs) {
    final var schedule = new TreeMap<ActivityDirectiveId, ActivityDirective>();

    for (final var activitySpec : activitySpecs) {
      schedule.put(
          new ActivityDirectiveId(_counter++),
          new ActivityDirective(activitySpec.getLeft(), activitySpec.getRight(), null, true));
    }

    return schedule;
  }
}
