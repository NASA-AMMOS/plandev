package gov.nasa.jpl.aerie.merlin.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.merlin.server.config.AppConfiguration;
import gov.nasa.jpl.aerie.merlin.server.config.PostgresStore;
import gov.nasa.jpl.aerie.merlin.server.config.Store;
import gov.nasa.jpl.aerie.merlin.server.http.LocalAppExceptionBindings;
import gov.nasa.jpl.aerie.merlin.server.http.MerlinBindings;
import gov.nasa.jpl.aerie.merlin.server.http.MissionModelRepositoryExceptionBindings;
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
import org.eclipse.jetty.server.Connector;
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
    // Configure an HTTP server.
    //default javalin jetty server has a QueuedThreadPool with maxThreads to 250
    final var server = new Server(new QueuedThreadPool(250));
    final var connector = new ServerConnector(server);
    connector.setPort(configuration.httpPort());
    //set idle timeout to be equal to the idle timeout of hasura
    connector.setIdleTimeout(180000);
    server.addBean(new LowResourceMonitor(server));
    server.insertHandler(new StatisticsHandler());
    server.setConnectors(new Connector[]{connector});
    final var javalin = Javalin.create(config -> {
      config.showJavalinBanner = false;
      if (configuration.enableJavalinDevLogging()) config.plugins.enableDevLogging();
      config.plugins.enableCors(cors -> cors.add(it -> it.anyHost()));
      config.plugins.register(merlinBindings);
      config.plugins.register(new LocalAppExceptionBindings());
      config.plugins.register(new MissionModelRepositoryExceptionBindings());
      config.jetty.server(() -> server);
    });

    // Start the HTTP server.
    javalin.start(configuration.httpPort());
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
