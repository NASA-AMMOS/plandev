package gov.nasa.jpl.aerie.scheduler.worker;

import java.net.URI;
import java.nio.file.Path;

import gov.nasa.jpl.aerie.scheduler.simulation.SchedulerSimulationReuseStrategy;
import gov.nasa.jpl.aerie.scheduler.server.config.PlanOutputMode;
import gov.nasa.jpl.aerie.scheduler.server.config.Store;

/**
 * controls behavior and connections of the entire scheduler worker
 *
 * @param simReuseStrategy how to reuse simulation results during/between scheduler runs (eg incremental sim)
 */
public record WorkerAppConfiguration(
    Store store,
    URI merlinGraphqlURI,
    Path merlinFileStore,
    PlanOutputMode outputMode,
    String hasuraGraphQlAdminSecret,
    int maxCachedSimulationEngines,
    SchedulerSimulationReuseStrategy simReuseStrategy
) { }
