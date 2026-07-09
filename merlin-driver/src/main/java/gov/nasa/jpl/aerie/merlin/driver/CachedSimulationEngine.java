package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanException;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.time.Instant;
import java.util.Map;

public record CachedSimulationEngine(
      Duration endsAt,
      Map<ActivityDirectiveId, ActivityDirective> activityDirectives,
      SimulationEngine simulationEngine,
      Topic<ActivityDirectiveId> activityTopic,
      MissionModel<?> missionModel,
      InMemorySimulationResourceManager resourceManager
  ) {
  public void freeze() {
    simulationEngine.close();
  }

  public static CachedSimulationEngine empty(final MissionModel<?> missionModel, final Instant simulationStartTime) {
    final SimulationEngine engine = new SimulationEngine(simulationStartTime, missionModel, null);

    // Specify a topic on which tasks can log the activity they're associated with.
    final var activityTopic = new Topic<ActivityDirectiveId>();
    try {
      engine.init(false);

      return new CachedSimulationEngine(
          Duration.MIN_VALUE,
          Map.of(),
          engine,
          new Topic<>(),
          missionModel,
          new InMemorySimulationResourceManager()
      );
    } catch (SpanException ex) {
      // Swallowing the spanException as the internal `spanId` is not user meaningful info.
      final var topics = missionModel.getTopics();
      final var directiveDetail = engine.getDirectiveDetailsFromSpan(activityTopic, topics, ex.spanId);
      if (directiveDetail.directiveId().isPresent()) {
        throw new SimulationException(
            Duration.ZERO,
            simulationStartTime,
            directiveDetail.directiveId().get(),
            directiveDetail.activityStackTrace(),
            ex.cause);
      }
      throw new SimulationException(Duration.ZERO, simulationStartTime, ex.cause);
    } catch (Throwable ex) {
      throw new SimulationException(Duration.ZERO, simulationStartTime, ex);
    }
  }
}
