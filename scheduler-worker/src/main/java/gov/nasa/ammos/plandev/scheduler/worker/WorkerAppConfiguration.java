package gov.nasa.ammos.plandev.scheduler.worker;

import java.net.URI;
import java.nio.file.Path;
import gov.nasa.ammos.plandev.scheduler.server.config.PlanOutputMode;
import gov.nasa.ammos.plandev.scheduler.server.config.Store;

public record WorkerAppConfiguration(
    Store store,
    URI merlinGraphqlURI,
    Path merlinFileStore,
    PlanOutputMode outputMode,
    String hasuraGraphQlAdminSecret,
    int maxCachedSimulationEngines
) { }
