package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.engine.SpanId;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record SimulationResultsComputerInputs(
    SimulationEngine engine,
    Instant simulationStartTime,
    Duration elapsedTime,
    Topic<ActivityDirectiveId> activityTopic,
    Map<Topic<?>, MissionModel.SerializableTopic<?>> serializableTopics,
    Map<ActivityDirectiveId, SpanId> activityDirectiveIdTaskIdMap,
    SimulationResourceManager resourceManager){

  public SimulationResultsInterface computeResults(final Set<String> resourceNames){
    return engine.computeResults(
        this.simulationStartTime(),
        this.elapsedTime(),
        this.activityTopic(),
        this.serializableTopics(),
        this.resourceManager
    );
  }

  public SimulationResultsInterface computeResults(){
    return engine.computeResults(
        this.simulationStartTime(),
        this.elapsedTime(),
        this.activityTopic(),
        this.serializableTopics(),
        this.resourceManager
    );
  }

  public SimulationEngine.SimulationActivityExtract computeActivitySimulationResults(){
    return engine.computeActivitySimulationResults(
        this.simulationStartTime(),
        this.activityTopic(),
        this.serializableTopics());
  }
}
