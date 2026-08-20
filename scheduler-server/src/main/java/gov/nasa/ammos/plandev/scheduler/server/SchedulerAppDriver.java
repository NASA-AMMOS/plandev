package gov.nasa.ammos.plandev.scheduler.server;

import java.net.URI;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.ammos.plandev.permissions.PermissionsService;
import gov.nasa.ammos.plandev.permissions.gql.GraphQLPermissionsService;
import gov.nasa.ammos.plandev.scheduler.server.config.AppConfiguration;
import gov.nasa.ammos.plandev.scheduler.server.config.PostgresStore;
import gov.nasa.ammos.plandev.scheduler.server.config.Store;
import gov.nasa.ammos.plandev.scheduler.server.http.SchedulerBindings;
import gov.nasa.ammos.plandev.scheduler.server.remotes.ResultsCellRepository;
import gov.nasa.ammos.plandev.scheduler.server.remotes.SpecificationRepository;
import gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.PostgresResultsCellRepository;
import gov.nasa.ammos.plandev.scheduler.server.remotes.postgres.PostgresSpecificationRepository;
import gov.nasa.ammos.plandev.scheduler.server.services.GenerateSchedulingLibAction;
import gov.nasa.ammos.plandev.scheduler.server.services.GraphQLMerlinDatabaseService;
import gov.nasa.ammos.plandev.scheduler.server.services.ScheduleAction;
import gov.nasa.ammos.plandev.scheduler.server.services.SchedulerService;
import gov.nasa.ammos.plandev.scheduler.server.services.SpecificationService;
import gov.nasa.ammos.plandev.scheduler.server.services.UnexpectedSubtypeError;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.eclipse.jetty.server.LowResourceMonitor;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.LoggerFactory;

/**
 * scheduler service entry point class; services pending scheduler requests until terminated
 */
public final class SchedulerAppDriver {

  /**
   * scheduler service entry point; services pending scheduler requests until terminated
   *
   * reads configuration options from the environment (if available, otherwise uses hardcoded defaults) to control how
   * the scheduler connects to its data stores or services scheduling requests
   *
   * this method never naturally returns; it will service requests until externally terminated (or exception)
   *
   * @param args command-line args passed to the executable
   *     [...] all arguments are ignored
   */
  public static void main(final String[] args) {
    //load the service configuration options
    final var appConfig = loadConfiguration();

    final var merlinDatabaseService = new GraphQLMerlinDatabaseService(appConfig.merlinGraphqlURI(), appConfig.hasuraGraphQlAdminSecret());
    final var permissionsService = new PermissionsService(new GraphQLPermissionsService(appConfig.merlinGraphqlURI(), appConfig.hasuraGraphQlAdminSecret()));

    final var stores = loadStores(appConfig);

    //create objects in each service abstraction layer (mirroring MerlinApp)
    final var specificationService = new SpecificationService(stores.specifications());
    final var schedulerService = new SchedulerService(stores.results());
    final var scheduleAction = new ScheduleAction(specificationService, schedulerService);

    final var generateSchedulingLibAction = new GenerateSchedulingLibAction(merlinDatabaseService);

    //establish bindings to the service layers
    final var bindings = new SchedulerBindings(
        specificationService,
        schedulerService,
        scheduleAction,
        generateSchedulingLibAction,
        permissionsService);

    // Configure the Jetty HTTP server
    // Default Javalin Jetty server has a QueuedThreadPool with maxThreads to 250
    final var jettyServer = new Server(new QueuedThreadPool(250));

    // Create an internal connector to speak within the docker network
    final var hasuraConnector = new ServerConnector(jettyServer);
    //set idle timeout to be equal to the idle timeout of hasura
    hasuraConnector.setIdleTimeout(180000);
    hasuraConnector.setPort(appConfig.httpPort());
    hasuraConnector.setName("hasura");
    jettyServer.addConnector(hasuraConnector);

    // Finish configuring the Jetty Server
    jettyServer.addBean(new LowResourceMonitor(jettyServer));
    jettyServer.insertHandler(new StatisticsHandler());

    // Create two Javalin instances: a private one for Hasura to communicate with and a public one for the health check
    final var schedulerServer = Javalin.create();
    final var healthCheckServer = Javalin.create();

    // Configure the Javalin instances
    schedulerServer.updateConfig(config -> { // the consumer lambda overlays additional config on the input javalinConfig
      config.showJavalinBanner = false;
      if (appConfig.enableJavalinDevLogging()) config.plugins.enableDevLogging();
      config.plugins.register(bindings);
      config.jetty.server(() -> jettyServer);
    });

    healthCheckServer.updateConfig(config -> {
      config.plugins.enableCors(cors -> cors.add(CorsPluginConfig::anyHost));
      config.routing.ignoreTrailingSlashes = true;
      config.routing.caseInsensitiveRoutes = true;
      config.showJavalinBanner = false;
    });

    // Set up the health check routes
    healthCheckServer.get("", ctx -> ctx.status(200));
    healthCheckServer.get("health", ctx -> ctx.status(200));

    // Tie the health checker into the scheduler server health
    schedulerServer.events(listener -> {
      listener.serverStarted(() -> healthCheckServer.start(8080));
      listener.serverStopping(healthCheckServer::close);
    });

    // Start the HTTP server. Port is unspecified to avoid overriding the Jetty configuration
    schedulerServer.start();

    // Ensure both servers are shut down when the JVM exits
    Runtime.getRuntime().addShutdownHook(new Thread(schedulerServer::close));
    Runtime.getRuntime().addShutdownHook(new Thread(healthCheckServer::close));
  }

  private record Stores(SpecificationRepository specifications, ResultsCellRepository results) { }

  private static Stores loadStores(
      final AppConfiguration config) {
    final var store = config.store();
    if (store instanceof final PostgresStore pgStore) {
      final var hikariConfig = new HikariConfig();
      hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
      hikariConfig.addDataSourceProperty("serverName", pgStore.server());
      hikariConfig.addDataSourceProperty("portNumber", pgStore.port());
      hikariConfig.addDataSourceProperty("databaseName", pgStore.database());
      hikariConfig.addDataSourceProperty("applicationName", "Scheduler Server");
      hikariConfig.setUsername(pgStore.user());
      hikariConfig.setPassword(pgStore.password());

      hikariConfig.setConnectionInitSql("set time zone 'UTC'");

      final var hikariDataSource = new HikariDataSource(hikariConfig);

      return new Stores(
          new PostgresSpecificationRepository(hikariDataSource),
          new PostgresResultsCellRepository(hikariDataSource));
    } else {
      throw new UnexpectedSubtypeError(Store.class, store);
    }
  }

  /**
   * fetch the value of the requested environment variable if available, otherwise return the given fallback
   *
   * @param key the name of the environment variable to fetch
   * @param fallback the value to use in case the requested environment variable does not exist in the environment
   * @return the value of the requested environment variable if it exists in the environment (even if it is the empty
   *     string), otherwise the specified fallback value
   */
  private static String getEnv(final String key, final String fallback) {
    final var env = System.getenv(key);
    return env == null ? fallback : env;
  }

  /**
   * collects configuration options from the environment
   *
   * any options not specified in the input stream fall back to the hard-coded defaults here
   *
   * @return a complete configuration object reflecting choices elected in the environment or the defaults
   */
  private static AppConfiguration loadConfiguration() {
    final var logger = LoggerFactory.getLogger(SchedulerAppDriver.class);
    return new AppConfiguration(
        Integer.parseInt(getEnv("SCHEDULER_PORT", "27185")),
        logger.isDebugEnabled(),
        new PostgresStore(
            getEnv("PLANDEV_DB", "plandev"),
            getEnv("PLANDEV_DB_HOST", getEnv("AERIE_DB_HOST", "postgres")),
            Integer.parseInt(getEnv("PLANDEV_DB_PORT", getEnv("AERIE_DB_PORT", "5432"))),
            getEnv("SCHEDULER_DB_USER", ""),
            getEnv("SCHEDULER_DB_PASSWORD", "")
        ),
        URI.create(getEnv("MERLIN_GRAPHQL_URL", "http://localhost:8080/v1/graphql")),
        getEnv("HASURA_GRAPHQL_ADMIN_SECRET", "")
    );
  }
}
