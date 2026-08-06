package gov.nasa.jpl.aerie.merlin.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.merlin.server.config.AppConfiguration;
import gov.nasa.jpl.aerie.merlin.server.config.PostgresStore;
import gov.nasa.jpl.aerie.merlin.server.config.Store;
import gov.nasa.jpl.aerie.merlin.server.http.MerlinBindings;
import gov.nasa.jpl.aerie.merlin.server.remotes.ConstraintRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.PlanRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.ResultsCellRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresConstraintRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresMissionModelRepository;
import gov.nasa.jpl.aerie.merlin.server.services.ValidationWorker;
import gov.nasa.jpl.aerie.permissions.PermissionsService;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresPlanRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresResultsCellRepository;
import gov.nasa.jpl.aerie.merlin.server.services.CachedSimulationService;
import gov.nasa.jpl.aerie.merlin.server.services.ConstraintAction;
import gov.nasa.jpl.aerie.merlin.server.services.ConstraintsDSLCompilationService;
import gov.nasa.jpl.aerie.merlin.server.services.GenerateConstraintsLibAction;
import gov.nasa.jpl.aerie.merlin.server.services.GetSimulationResultsAction;
import gov.nasa.jpl.aerie.merlin.server.services.LocalConstraintService;
import gov.nasa.jpl.aerie.merlin.server.services.LocalMissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.LocalPlanService;
import gov.nasa.jpl.aerie.merlin.server.services.TypescriptCodeGenerationServiceAdapter;
import gov.nasa.jpl.aerie.merlin.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.permissions.gql.GraphQLPermissionsService;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.eclipse.jetty.server.LowResourceMonitor;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;

public final class AerieAppDriver {

  public static void main(final String[] args) {
    // Fetch application configuration properties.
    final var configuration = loadConfiguration();
    final var stores = loadStores(configuration);

    final var missionModelController = new LocalMissionModelService(
        configuration.merlinFileStore(),
        stores.missionModels(),
        configuration.untruePlanStart());

    if (configuration.enableContinuousValidationThread()) {
      final var validationWorker = new ValidationWorker(
          missionModelController,
          configuration.validationThreadPollingPeriod());
      final var thread = new Thread(validationWorker::workerLoop);
      thread.setDaemon(true);
      thread.start();
    }

    final var planController = new LocalPlanService(stores.plans());

    final var typescriptCodeGenerationService = new TypescriptCodeGenerationServiceAdapter(missionModelController, planController);

    final ConstraintsDSLCompilationService constraintsDSLCompilationService;
    try {
      constraintsDSLCompilationService = new ConstraintsDSLCompilationService(typescriptCodeGenerationService);
    } catch (IOException e) {
      throw new Error("Failed to start ConstraintsDSLCompilationService", e);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(constraintsDSLCompilationService::close));

    // Assemble the core non-web object graph.
    final var simulationController = new CachedSimulationService(stores.results());
    final var simulationAction = new GetSimulationResultsAction(
        planController,
        simulationController
    );
    final var constraintService = new LocalConstraintService(
        stores.constraints()
    );
    final var constraintAction = new ConstraintAction(
      constraintsDSLCompilationService,
      constraintService,
      planController,
      simulationController
    );
    final var generateConstraintsLibAction = new GenerateConstraintsLibAction(typescriptCodeGenerationService);
    final var permissionsService = new PermissionsService(
        new GraphQLPermissionsService(configuration.hasuraGraphqlURI(), configuration.hasuraGraphQlAdminSecret()));
    final var merlinBindings = new MerlinBindings(
        missionModelController,
        planController,
        simulationAction,
        generateConstraintsLibAction,
        constraintAction,
        permissionsService
    );
    // Configure the Jetty HTTP server.
    // Default Javalin Jetty server has a QueuedThreadPool with maxThreads to 250
    final var jettyServer = new Server(new QueuedThreadPool(250));

    // Create an internal connector to speak within the docker network
    final var hasuraConnector = new ServerConnector(jettyServer);
    //set idle timeout to be equal to the idle timeout of hasura
    hasuraConnector.setIdleTimeout(180000);
    hasuraConnector.setPort(configuration.httpPort());
    hasuraConnector.setName("hasura");
    jettyServer.addConnector(hasuraConnector);

    // Finish configuring Jetty Server
    jettyServer.addBean(new LowResourceMonitor(jettyServer));
    jettyServer.insertHandler(new StatisticsHandler());

    // Create two Javalin instances: a private one for Hasura to communicate with and a public one for the health check
    final var merlinServer = Javalin.create();
    final var healthCheckServer = Javalin.create();

    // Configure the Javalin instances
    merlinServer.updateConfig(config -> {
      config.showJavalinBanner = false;
      if (configuration.enableJavalinDevLogging()) config.plugins.enableDevLogging();
      config.plugins.register(merlinBindings);
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

    // Tie the health checker into the merlin server health
    merlinServer.events(listener -> {
      listener.serverStarting(() -> healthCheckServer.start(8080));
      listener.serverStopping(healthCheckServer::close);
    });

    // Start the HTTP server. Port is unspecified to avoid overriding the Jetty configuration
    merlinServer.start();

    // Ensure both servers are shut down when the JVM exits
    Runtime.getRuntime().addShutdownHook(new Thread(merlinServer::close));
    Runtime.getRuntime().addShutdownHook(new Thread(healthCheckServer::close));
  }

  private record Stores (
      PlanRepository plans,
      MissionModelRepository missionModels,
      ResultsCellRepository results,
      ConstraintRepository constraints
  ) {}

  private static Stores loadStores(final AppConfiguration config) {
    final var store = config.store();
    if (store instanceof PostgresStore c) {
      final var hikariConfig = new HikariConfig();
      hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
      hikariConfig.addDataSourceProperty("serverName", c.server());
      hikariConfig.addDataSourceProperty("portNumber", c.port());
      hikariConfig.addDataSourceProperty("databaseName", c.database());
      hikariConfig.addDataSourceProperty("applicationName", "Merlin Server");

      hikariConfig.setUsername(c.user());
      hikariConfig.setPassword(c.password());

      hikariConfig.setConnectionInitSql("set time zone 'UTC'");

      final var hikariDataSource = new HikariDataSource(hikariConfig);

      return new Stores(
          new PostgresPlanRepository(hikariDataSource, config.merlinFileStore()),
          new PostgresMissionModelRepository(hikariDataSource),
          new PostgresResultsCellRepository(hikariDataSource),
          new PostgresConstraintRepository(hikariDataSource));
    } else {
      throw new UnexpectedSubtypeError(Store.class, store);
    }
  }

  private static String getEnv(final String key, final String fallback) {
    final var env = System.getenv(key);
    return env == null ? fallback : env;
  }

  private static AppConfiguration loadConfiguration() {
    final var logger = LoggerFactory.getLogger(AerieAppDriver.class);
    return new AppConfiguration(
        Integer.parseInt(getEnv("MERLIN_PORT", "27183")),
        logger.isDebugEnabled(),
        Path.of(getEnv("MERLIN_LOCAL_STORE", "/usr/src/app/merlin_file_store")),
        new PostgresStore(getEnv("AERIE_DB_HOST", "postgres"),
                          getEnv("MERLIN_DB_USER", ""),
                          Integer.parseInt(getEnv("AERIE_DB_PORT", "5432")),
                          getEnv("MERLIN_DB_PASSWORD", ""),
                          "aerie"),
        Instant.parse(getEnv("UNTRUE_PLAN_START", "")),
        URI.create(getEnv("HASURA_GRAPHQL_URL", "http://localhost:8080/v1/graphql")),
        getEnv("HASURA_GRAPHQL_ADMIN_SECRET", ""),
        Boolean.parseBoolean(getEnv("ENABLE_CONTINUOUS_VALIDATION_THREAD", "true")),
        Integer.parseInt(getEnv("VALIDATION_THREAD_POLLING_PERIOD", "500"))
    );
  }
}
