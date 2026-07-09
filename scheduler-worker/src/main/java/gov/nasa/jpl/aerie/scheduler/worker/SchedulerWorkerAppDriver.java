package gov.nasa.jpl.aerie.scheduler.worker;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.scheduler.simulation.SchedulerSimulationReuseStrategy;
import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;
import gov.nasa.jpl.aerie.scheduler.server.config.PlanOutputMode;
import gov.nasa.jpl.aerie.scheduler.server.config.PostgresStore;
import gov.nasa.jpl.aerie.scheduler.server.config.Store;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.PostgresResultsCellRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.PostgresSpecificationRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.SpecificationRevisionData;
import gov.nasa.jpl.aerie.scheduler.server.services.GraphQLMerlinDatabaseService;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleRequest;
import gov.nasa.jpl.aerie.scheduler.server.services.SpecificationService;
import gov.nasa.jpl.aerie.scheduler.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.scheduler.worker.postgres.PostgresSchedulingRequestNotificationPayload;
import gov.nasa.jpl.aerie.scheduler.worker.services.SchedulingDSLCompilationService;
import gov.nasa.jpl.aerie.scheduler.worker.services.SynchronousSchedulerAgent;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchedulerWorkerAppDriver {
  private static final Logger logger = LoggerFactory.getLogger(SchedulerWorkerAppDriver.class);

  public static void main(String[] args) throws Exception {
    final var config = loadConfiguration();

    final var merlinDatabaseService = new GraphQLMerlinDatabaseService(config.merlinGraphqlURI(), config.hasuraGraphQlAdminSecret());

    final SchedulingDSLCompilationService schedulingDSLCompilationService;
    try {
      schedulingDSLCompilationService = new SchedulingDSLCompilationService();
    } catch (final IOException e) {
      throw new Error("Failed to start SchedulingDSLCompilationService", e);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(schedulingDSLCompilationService::close));

    final var store = config.store();
    if (!(store instanceof final PostgresStore postgresStore)) {
      throw new UnexpectedSubtypeError(Store.class, store);
    }
    final var hikariConfig = new HikariConfig();
    hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
    hikariConfig.addDataSourceProperty("serverName", postgresStore.server());
    hikariConfig.addDataSourceProperty("portNumber", postgresStore.port());
    hikariConfig.addDataSourceProperty("databaseName", postgresStore.database());
    hikariConfig.addDataSourceProperty("applicationName", "Scheduler Worker");
    hikariConfig.setUsername(postgresStore.user());
    hikariConfig.setPassword(postgresStore.password());
    hikariConfig.setMaximumPoolSize(2);

    hikariConfig.setConnectionInitSql("set time zone 'UTC'");

    final var hikariDataSource = new HikariDataSource(hikariConfig);

    final var stores = new Stores(
      new PostgresSpecificationRepository(hikariDataSource),
      new PostgresResultsCellRepository(hikariDataSource));

    final var specificationService = new SpecificationService(stores.specifications());
    final var scheduleAgent = new SynchronousSchedulerAgent(specificationService,
        merlinDatabaseService,
        config.merlinFileStore(),
        config.outputMode(),
        schedulingDSLCompilationService,
        config.simReuseStrategy());

    final var notificationQueue = new LinkedBlockingQueue<PostgresSchedulingRequestNotificationPayload>();
    final var listenAction = new ListenSchedulerCapability(hikariDataSource, notificationQueue);
    final var canceledListener = new SchedulingCanceledListener();
    final var listenThread = listenAction.registerListener(canceledListener);

    try(final var app = Javalin.create().start(8080)) {
      app.get("/health", ctx -> ctx.status(200));

      while (listenThread.isAlive()) {
        final var notification = notificationQueue.poll(1, TimeUnit.MINUTES);
        if (notification == null) continue;
        final var specificationRevision = notification.specificationRevision();
        final var planRevision = notification.planRevision();
        final var specificationId = new SpecificationId(notification.specificationId());
        final var analysisId = notification.analysisId();

        // Register as early as possible to avoid potentially missing a canceled signal
        canceledListener.register(specificationId);

        final Optional<ResultsProtocol.OwnerRole> owner = stores.results().claim(analysisId);
        if (owner.isEmpty()) {
          canceledListener.unregister();
          continue;
        }

        final var revisionData = new SpecificationRevisionData(specificationRevision, planRevision);
        final ResultsProtocol.WriterRole writer = owner.get();
        try {
          scheduleAgent.schedule(
              new ScheduleRequest(specificationId, revisionData),
              writer,
              canceledListener,
              config.maxCachedSimulationEngines());
        } catch (final Throwable ex) {
          ex.printStackTrace(System.err);
          writer.failWith(b -> b
              .type("UNEXPECTED_SCHEDULER_EXCEPTION")
              .message("Something went wrong while scheduling")
              .trace(ex));
        }
        finally {
          canceledListener.unregister();
        }
      }
    } finally {
      // Kill the listen thread
      listenThread.interrupt();
    }
  }

  private static String getEnv(final String key, final String fallback){
    final var env = System.getenv(key);
    return env == null ? fallback : env;
  }

  /**
   * parses any worker configuration options from env vars, instilling defaults if not found
   *
   * @return a complete worker configuration object, with all fields filled from env vars or defaults
   */
  private static WorkerAppConfiguration loadConfiguration() {
    int maxNbCachedSimulationEngine = Integer.parseInt(getEnv("MAX_NB_CACHED_SIMULATION_ENGINES", "1"));
    if (maxNbCachedSimulationEngine < 1) {
      logger.warn("MAX_NB_CACHED_SIMULATION_ENGINES is " + maxNbCachedSimulationEngine + " but minimum is 1. Setting to 1.");
      maxNbCachedSimulationEngine = 1;
    }
    return new WorkerAppConfiguration(
        new PostgresStore(getEnv("AERIE_DB_HOST", "postgres"),
                          getEnv("SCHEDULER_DB_USER", ""),
                          Integer.parseInt(getEnv("AERIE_DB_PORT", "5432")),
                          getEnv("SCHEDULER_DB_PASSWORD", ""),
                          "aerie"),
        URI.create(getEnv("MERLIN_GRAPHQL_URL", "http://localhost:8080/v1/graphql")),
        Path.of(getEnv("MERLIN_LOCAL_STORE", "/usr/src/app/merlin_file_store")),
        PlanOutputMode.valueOf((getEnv("SCHEDULER_OUTPUT_MODE", "CreateNewOutputPlan"))),
        getEnv("HASURA_GRAPHQL_ADMIN_SECRET", ""),
        maxNbCachedSimulationEngine,
        SchedulerSimulationReuseStrategy.valueOf(getEnv(
            "SCHEDULER_SIM_REUSE_STRATEGY", SchedulerSimulationReuseStrategy.Incremental.name())));
  }
}
