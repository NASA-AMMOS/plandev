package gov.nasa.jpl.aerie.merlin.driver.develop;

import gov.nasa.ammos.aerie.simulation.protocol.ProfileSegment;
import gov.nasa.ammos.aerie.simulation.protocol.ResourceProfile;
import gov.nasa.ammos.aerie.simulation.protocol.Results;
import gov.nasa.ammos.aerie.simulation.protocol.Schedule;
import gov.nasa.ammos.aerie.simulation.protocol.Simulator;
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

public class MerlinDriverAdapter<Config, Model> implements Simulator {
  private final ModelType<Config, Model> modelType;
  private final Config config;
  private final Instant startTime;
  private final Duration duration;

  public MerlinDriverAdapter(ModelType<Config, Model> modelType, Config config, Instant startTime, Duration duration) {
    this.modelType = modelType;
    this.config = config;
    this.startTime = startTime;
    this.duration = duration;
  }

  @Override
  public Results simulate(Schedule schedule, Supplier<Boolean> isCancelled) {
        final var builder = new MissionModelBuilder();
        final var builtModel = builder.build(modelType.instantiate(startTime, config, builder), DirectiveTypeRegistry.extract(modelType));
        SimulationResults results = SimulationDriver.simulate(
                builtModel,
                adaptSchedule(schedule),
                startTime,
                duration,
                startTime,
                duration,
                isCancelled
        );
        return adaptResults(results);
    }

  private Map<ActivityDirectiveId, ActivityDirective> adaptSchedule(Schedule schedule) {
        final var res = new HashMap<ActivityDirectiveId, ActivityDirective>();
        for (var entry : schedule.entries()) {
            res.put(new ActivityDirectiveId(entry.id()),
                    new ActivityDirective(
                            entry.startOffset(),
                            entry.directive().type(),
                            entry.directive().arguments(),
                            null,
                            true));
        }
        return res;
    }

    private Results adaptResults(SimulationResults results) {
        return new Results(
            results.startTime,
            results.duration,
            results
                .realProfiles
                .entrySet()
                .stream()
                .map($ -> Pair.of($.getKey(), new ResourceProfile<>($.getValue().schema(), adaptProfile($))))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
            results
                .discreteProfiles
                .entrySet()
                .stream()
                .map($ -> Pair.of($.getKey(), new ResourceProfile<>($.getValue().schema(), adaptProfile($))))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
            results
                .simulatedActivities
                .entrySet()
                .stream()
                .map($ -> Pair.of($.getKey().id(), adaptSimulatedActivity($.getValue())))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue))
        );
    }

  private static <T> List<ProfileSegment<T>> adaptProfile(Map.Entry<String, gov.nasa.jpl.aerie.merlin.driver.develop.resources.ResourceProfile<T>> $) {
    return $.getValue().segments().stream().map(MerlinDriverAdapter::adaptSegment).toList();
  }


  private static <T> ProfileSegment<T> adaptSegment(gov.nasa.jpl.aerie.merlin.driver.develop.engine.ProfileSegment<T> segment) {
        return new ProfileSegment<>(segment.extent(), segment.dynamics());
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
}
