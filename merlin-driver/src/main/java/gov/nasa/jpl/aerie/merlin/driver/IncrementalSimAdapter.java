package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.ammos.aerie.simulation.protocol.ProfileSegment;
import gov.nasa.ammos.aerie.simulation.protocol.Results;
import gov.nasa.ammos.aerie.simulation.protocol.Schedule;
import gov.nasa.ammos.aerie.simulation.protocol.Simulator;
import gov.nasa.ammos.aerie.simulation.protocol.ResourceProfile;

import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class IncrementalSimAdapter implements Simulator {
  private final SimulationDriver<?> driver;
  private boolean calledSimulate = false;
  public <Config, Model> IncrementalSimAdapter(ModelType<Config, Model> modelType, Config config, Instant startTime, Duration duration) {
    final var builder = new MissionModelBuilder();
    final var missionModel = builder.build(modelType.instantiate(startTime, config, builder), DirectiveTypeRegistry.extract(modelType));
    this.driver = new SimulationDriver<>(missionModel, startTime, duration);
  }

  @Override
  public Results simulate(Schedule schedule, Supplier<Boolean> isCancelled) {
    return simulateMap(adaptSchedule(schedule), isCancelled);
  }

  private Results adaptResults(SimulationResultsInterface results) {
    return new Results(
        results.getStartTime(),
        results.getDuration(),
        results
            .getRealProfiles()
            .entrySet()
            .stream()
            .map($ -> Pair.of($.getKey(), new ResourceProfile<>($.getValue().schema(), adaptProfile($))))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
        results
            .getDiscreteProfiles()
            .entrySet()
            .stream()
            .map($ -> Pair.of($.getKey(), new ResourceProfile<>($.getValue().schema(), adaptProfile($))))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
        results
            .getSimulatedActivities()
            .entrySet()
            .stream()
            .map($ -> Pair.of($.getKey().id(), adaptSimulatedActivity($.getValue())))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue))
    );
  }

  private gov.nasa.ammos.aerie.simulation.protocol.SimulatedActivity adaptSimulatedActivity(ActivityInstance simulatedActivity) {
    return new gov.nasa.ammos.aerie.simulation.protocol.SimulatedActivity(
        simulatedActivity.type(),
        simulatedActivity.arguments(),
        simulatedActivity.start(),
        simulatedActivity.duration(),
        simulatedActivity.parentId() == null ? null : simulatedActivity.parentId().id(),
        simulatedActivity.childIds().stream().map(ActivityInstanceId::id).toList(),
        simulatedActivity.directiveId().map(ActivityDirectiveId::id),
        simulatedActivity.computedAttributes()
    );
  }

  private static <T> List<ProfileSegment<T>> adaptProfile(Map.Entry<String, gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile<T>> $) {
    return $.getValue().segments().stream().map(IncrementalSimAdapter::adaptSegment).toList();
  }

  private static <T> ProfileSegment<T> adaptSegment(gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment<T> segment) {
    return new ProfileSegment<>(segment.extent(), segment.dynamics());
  }

  private Map<ActivityDirectiveId, ActivityDirective> adaptSchedule(Schedule schedule) {
    final var res = new HashMap<ActivityDirectiveId, ActivityDirective>();
    for (var entry : schedule.entries()) {
      res.put(
          new ActivityDirectiveId(entry.id()),
          new ActivityDirective(
              entry.startOffset(),
              entry.directive().type(),
              entry.directive().arguments(),
              null,
              true));
    }
    return res;
  }

  public void initSimulation(final Duration duration) {
    driver.initSimulation(duration); // TODO commenting this out causes tests to fail, despite additional call in simulate method. Hmm....
  }

  public Results simulateMap(final Map<ActivityDirectiveId, ActivityDirective> schedule, final Supplier<Boolean> isCancelled) {
    if (!calledSimulate) {
      return simulateInternal(schedule, driver.getStartTime(), driver.getPlanDuration(), driver.getStartTime(), driver.getPlanDuration());
    } else {
      initSimulation(driver.getPlanDuration());
      final var schedule$ = new HashMap<ActivityDirectiveId, ActivityDirective>();
      for (final var entry : schedule.entrySet()) {
        schedule$.put(new ActivityDirectiveId(entry.getKey().id()), entry.getValue());
      }
      return adaptResults(driver.diffAndSimulate(
          schedule$, driver.getStartTime(), driver.getPlanDuration(),
          driver.getStartTime(), driver.getPlanDuration(),
          true, isCancelled, $ -> {},
          new InMemorySimulationResourceManager()));
    }
  }

  private Results simulateInternal(
      final Map<ActivityDirectiveId, ActivityDirective> schedule,
      final Instant simulationStartTime,
      final Duration simulationDuration,
      final Instant planStartTime,
      final Duration planDuration)
  {
    if (calledSimulate) throw new IllegalStateException("Should not call simulate twice");
    calledSimulate = true;
    return adaptResults(driver.simulate(schedule, simulationStartTime, simulationDuration, planStartTime, planDuration));
  }
}
