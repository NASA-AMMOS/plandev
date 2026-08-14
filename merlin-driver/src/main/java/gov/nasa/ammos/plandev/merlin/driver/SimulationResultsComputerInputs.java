package gov.nasa.ammos.plandev.merlin.driver;

import gov.nasa.ammos.plandev.merlin.driver.engine.SimulationEngine;
import gov.nasa.ammos.plandev.merlin.driver.engine.SpanId;
import gov.nasa.ammos.plandev.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.ammos.plandev.merlin.protocol.driver.Topic;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record SimulationResultsComputerInputs(
    SimulationEngine engine,
    Instant simulationStartTime,
    Topic<ActivityDirectiveId> activityTopic,
    Iterable<MissionModel.SerializableTopic<?>> serializableTopics,
    Map<ActivityDirectiveId, SpanId> activityDirectiveIdTaskIdMap,
    SimulationResourceManager resourceManager){

  public SimulationResults computeResults(final Set<String> resourceNames){
    return engine.computeResults(
        this.simulationStartTime(),
        this.activityTopic(),
        this.serializableTopics(),
        this.resourceManager,
        resourceNames
    );
  }

  public SimulationResults computeResults(){
    return engine.computeResults(
        this.simulationStartTime(),
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
