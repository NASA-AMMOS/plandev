package gov.nasa.jpl.aerie.merlin.driver.retracing;

import gov.nasa.ammos.aerie.simulation.protocol.ProfileSegment;
import gov.nasa.ammos.aerie.simulation.protocol.ResourceProfile;
import gov.nasa.ammos.aerie.simulation.protocol.Results;
import gov.nasa.ammos.aerie.simulation.protocol.Schedule;
import gov.nasa.ammos.aerie.simulation.protocol.Simulator;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.*;

import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class RetracingDriverAdapter<Config, Model> implements Simulator {
  private final Config config;
  private final Instant startTime;
  private final Duration duration;
  private final MissionModel<Model> model;
  private final RetracingSimulationDriver.Cache cache;

  public RetracingDriverAdapter(ModelType<Config, Model> modelType, Config config, Instant startTime, Duration duration) {
    this.config = config;
    this.startTime = startTime;
    this.duration = duration;
    final var builder = new MissionModelBuilder();
    final var builtModel = builder.build(modelType.instantiate(startTime, config, builder), DirectiveTypeRegistry.extract(modelType));
    this.model = builtModel;
    this.cache = RetracingSimulationDriver.Cache.init(builtModel);
  }

  @Override
  public Results simulate(Schedule schedule, Supplier<Boolean> isCancelled) {
        final var results = RetracingSimulationDriver.simulate(
                model,
                schedule,
                startTime,
                duration,
                startTime,
                duration,
                isCancelled,
                cache
        );
        return adaptResults(results);
    }

    private Results adaptResults(SimulationResults results) {
        return new Results(
                results.startTime,
                results.duration,
                results.realProfiles.entrySet().stream().map($ -> Pair.of($.getKey(), new ResourceProfile<>($.getValue().getKey(), adaptProfile($)))).collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
                results.discreteProfiles.entrySet().stream().map($ -> Pair.of($.getKey(), new ResourceProfile<>($.getValue().getKey(), adaptProfile($)))).collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
                results.simulatedActivities.entrySet().stream().map($ -> Pair.of($.getKey().id(), adaptSimulatedActivity($.getValue()))).collect(Collectors.toMap(Pair::getKey, Pair::getValue))
        );
    }

    private static <T> List<ProfileSegment<T>> adaptProfile(Map.Entry<String, Pair<ValueSchema, List<gov.nasa.jpl.aerie.merlin.driver.retracing.engine.ProfileSegment<T>>>> $) {
        return $.getValue().getValue().stream().map(RetracingDriverAdapter::adaptSegment).toList();
    }

    private static <T> ProfileSegment<T> adaptSegment(gov.nasa.jpl.aerie.merlin.driver.retracing.engine.ProfileSegment<T> segment) {
        return new ProfileSegment<>(segment.extent(), segment.dynamics());
    }

    private gov.nasa.ammos.aerie.simulation.protocol.SimulatedActivity adaptSimulatedActivity(SimulatedActivity simulatedActivity) {
        return new gov.nasa.ammos.aerie.simulation.protocol.SimulatedActivity(
                simulatedActivity.type(),
                simulatedActivity.arguments(),
                simulatedActivity.start(),
                simulatedActivity.duration(),
                simulatedActivity.parentId() == null ? null : simulatedActivity.parentId().id(),
                simulatedActivity.childIds().stream().map(SimulatedActivityId::id).toList(),
                simulatedActivity.directiveId().map(ActivityDirectiveId::id),
                simulatedActivity.computedAttributes()
        );
    }
}
