package gov.nasa.jpl.aerie.scheduler.server;

import java.net.URI;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.permissions.gql.GraphQLPermissionsService;
import gov.nasa.jpl.aerie.scheduler.server.config.AppConfiguration;
import gov.nasa.jpl.aerie.scheduler.server.config.PostgresStore;
import gov.nasa.jpl.aerie.scheduler.server.config.Store;
import gov.nasa.jpl.aerie.scheduler.server.http.SchedulerBindings;
import gov.nasa.jpl.aerie.scheduler.server.remotes.ResultsCellRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.SpecificationRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.PostgresResultsCellRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.PostgresSpecificationRepository;
import gov.nasa.jpl.aerie.scheduler.server.services.GenerateSchedulingLibAction;
import gov.nasa.jpl.aerie.scheduler.server.services.GraphQLMerlinDatabaseService;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleAction;
import gov.nasa.jpl.aerie.scheduler.server.services.SchedulerService;
import gov.nasa.jpl.aerie.scheduler.server.services.SpecificationService;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import io.javalin.Javalin;
import org.eclipse.jetty.server.Connector;
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
    final var config = loadConfiguration();

    final var merlinDatabaseService = new GraphQLMerlinDatabaseService(config.merlinGraphqlURI(), config.hasuraGraphQlAdminSecret());
    final var permissionsService = new PermissionsService(new GraphQLPermissionsService(config.merlinGraphqlURI(), config.hasuraGraphQlAdminSecret()));

    final var stores = loadStores(config);

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

    //default javalin jetty server has a QueuedThreadPool with maxThreads to 250
    final var server = new Server(new QueuedThreadPool(250));
    final var connector = new ServerConnector(server);
    connector.setPort(config.httpPort());
    //set idle timeout to be equal to the idle timeout of hasura
    connector.setIdleTimeout(180000);
    server.addBean(new LowResourceMonitor(server));
    server.insertHandler(new StatisticsHandler());
    server.setConnectors(new Connector[]{connector});
    //configure the http server (the consumer lambda overlays additional config on the input javalinConfig)
    final var javalin = Javalin.create(javalinConfig -> {
      javalinConfig.showJavalinBanner = false;
      if (config.enableJavalinDevLogging()) javalinConfig.plugins.enableDevLogging();
      javalinConfig.plugins.enableCors(cors -> cors.add(it -> it.anyHost())); //TODO: probably don't want literally any cross-origin request...
      javalinConfig.plugins.register(bindings);
      javalinConfig.jetty.server(() -> server);
      //TODO: exception handling (shxould elevate/reuse from MerlinApp for consistency?)
    });

    //start the http server and handle requests as configured above
    javalin.start(config.httpPort());
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
        new PostgresStore(getEnv("AERIE_DB_HOST", "postgres"),
                          getEnv("SCHEDULER_DB_USER", ""),
                          Integer.parseInt(getEnv("AERIE_DB_PORT", "5432")),
                          getEnv("SCHEDULER_DB_PASSWORD", ""),
                          "aerie"),
        URI.create(getEnv("MERLIN_GRAPHQL_URL", "http://localhost:8080/v1/graphql")),
        getEnv("HASURA_GRAPHQL_ADMIN_SECRET", "")
    );
  }
}
