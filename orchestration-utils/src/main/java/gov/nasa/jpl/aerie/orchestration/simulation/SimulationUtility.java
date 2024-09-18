package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationDriver;
import gov.nasa.jpl.aerie.merlin.driver.SimulationException;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.InMemorySimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.driver.resources.SimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.driver.resources.StreamingSimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.Plan;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SimulationUtility implements AutoCloseable {
  private final ExecutorService exec;
  private final SimulationResourceManager rmgr;

  /**
   * Create a new SimulationUtility that manages resources using an InMemorySimulationResourceManager.
   */
  public SimulationUtility() {
    this.exec = Executors.newSingleThreadExecutor();
    rmgr = new InMemorySimulationResourceManager();
  }

  /**
   * Create a new SimulationUtility that manages resources using a StreamingSimulationResourceManager.
   * @param resourceStreamer a Consumer defining how the ResourceManager will stream resources.
   */
  public SimulationUtility(ResourceFileStreamer resourceStreamer) {
    this.exec = Executors.newSingleThreadExecutor();
    rmgr = new StreamingSimulationResourceManager(resourceStreamer);
  }

  /**
   * Simulate a plan. Simulation will not be cancelable.
   * @param model The mission model to be used during simulation
   * @param plan The plan to simulate. Contains the simulation configuration
   * @return A Future to get the SimulationResults
   */
  public Future<SimulationResults> simulate(MissionModel<?> model, Plan plan) {
    return simulate(model, plan, () -> false, d -> {});
  }

  /**
   * Simulate a plan
   * @param model The mission model to be used during simulation
   * @param plan The plan to simulate. Contains the simulation configuration
   * @param canceledListener A boolean supplier to permit canceling of the simulation
   * @param extentConsumer A duration consumer to receive updates on how much time has elapsed within the simulation
   * @return A Future to get the SimulationResults
   */
  public Future<SimulationResults> simulate(
      MissionModel<?> model,
      Plan plan,
      Supplier<Boolean> canceledListener,
      Consumer<Duration> extentConsumer
  ) {
    final var simulationDuration = Duration.of(plan.simulationStartTimestamp
                                                   .microsUntil(plan.simulationEndTimestamp), Duration.MICROSECOND);
    final var resultsThread = new Callable<SimulationResults>() {
      @Override
      public SimulationResults call() {
        return SimulationDriver.simulate(
            model,
            plan.activityDirectives(),
            plan.simulationStartTimestamp.toInstant(),
            simulationDuration,
            plan.planStartInstant(),
            plan.duration(),
            canceledListener,
            extentConsumer,
            rmgr);
      }
    };

    return exec.submit(resultsThread);
  }

  /**
   * Format a SimulationException into a JSON object.
   */
  public static JsonObject formatSimulationException(SimulationException ex) {
     final var dataBuilder = Json.createObjectBuilder()
                                 .add("elapsedTime", SimulationException.formatDuration(ex.elapsedTime))
                                 .add("utcTimeDoy", SimulationException.formatInstant(ex.instant));
      ex.directiveId.ifPresent(directiveId -> dataBuilder.add("executingDirectiveId", directiveId.id()));
      ex.activityType.ifPresent(activityType -> dataBuilder.add("executingActivityType", activityType));
      ex.activityStackTrace.ifPresent(activityStackTrace -> dataBuilder.add("activityStackTrace", activityStackTrace));

      final var sw = new StringWriter();
      ex.cause.printStackTrace(new PrintWriter(sw, true));
      final var stackTrace = sw.toString();

      return Json.createObjectBuilder()
                 .add("type", "SIMULATION_EXCEPTION")
                 .add("message", ex.cause.getMessage())
                 .add("data", dataBuilder)
                 .add("trace", stackTrace)
                 .build();
  }

  @Override
  public void close() {
    exec.close();
  }
}
