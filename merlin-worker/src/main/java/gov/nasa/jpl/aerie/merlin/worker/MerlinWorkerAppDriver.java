package gov.nasa.jpl.aerie.merlin.worker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.merlin.driver.resources.StreamingSimulationResourceManager;
import gov.nasa.jpl.aerie.merlin.server.ResultsProtocol;
import gov.nasa.jpl.aerie.merlin.server.config.PostgresStore;
import gov.nasa.jpl.aerie.merlin.server.config.Store;
import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresMissionModelRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresPlanRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresPlanRevisionData;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresResultsCellRepository;
import gov.nasa.jpl.aerie.merlin.server.services.LocalMissionModelService;
import gov.nasa.jpl.aerie.merlin.server.services.LocalPlanService;
import gov.nasa.jpl.aerie.merlin.server.services.SimulationAgent;
import gov.nasa.jpl.aerie.merlin.server.services.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.merlin.worker.postgres.PostgresProfileStreamer;
import gov.nasa.jpl.aerie.merlin.worker.postgres.PostgresReporter;
import gov.nasa.jpl.aerie.merlin.worker.postgres.PostgresSimulationNotificationPayload;
import gov.nasa.jpl.aerie.merlin.worker.postgres.ThreadedReporter;
import io.javalin.Javalin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class MerlinWorkerAppDriver {
  public static void main(String[] args) throws InterruptedException {
    final var configuration = loadConfiguration();
    final var store = configuration.store();

    if (!(store instanceof final PostgresStore postgresStore)) {
      throw new UnexpectedSubtypeError(Store.class, store);
    }
    final var hikariConfig = new HikariConfig();
    hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
    hikariConfig.addDataSourceProperty("serverName", postgresStore.server());
    hikariConfig.addDataSourceProperty("portNumber", postgresStore.port());
    hikariConfig.addDataSourceProperty("databaseName", postgresStore.database());
    hikariConfig.addDataSourceProperty("applicationName", "Merlin Server");
    hikariConfig.setUsername(postgresStore.user());
    hikariConfig.setPassword(postgresStore.password());
    hikariConfig.setMaximumPoolSize(3);

    hikariConfig.setConnectionInitSql("set time zone 'UTC'");

    final var hikariDataSource = new HikariDataSource(hikariConfig);

    final var stores = new Stores(
        new PostgresPlanRepository(hikariDataSource, configuration.merlinFileStore()),
        new PostgresMissionModelRepository(hikariDataSource),
        new PostgresResultsCellRepository(hikariDataSource));

    final var missionModelController = new LocalMissionModelService(
        configuration.merlinFileStore(),
        stores.missionModels(),
        configuration.untruePlanStart()
    );
    final var planController = new LocalPlanService(stores.plans());
    final var simulationAgent = new SimulationAgent(
        planController,
        missionModelController,
        configuration.simulationProgressPollPeriodMillis());

    final var notificationQueue = new LinkedBlockingQueue<PostgresSimulationNotificationPayload>();
    final var listenAction = new ListenSimulationCapability(hikariDataSource, notificationQueue);
    final var canceledListener = new SimulationCanceledListener();
    final var listenThread = listenAction.registerListener(canceledListener);

    try (final var app = Javalin.create().start(8080)) {
      app.get("/health", ctx -> ctx.status(200));

      while (listenThread.isAlive()) {
        final var notification = notificationQueue.poll(1, TimeUnit.MINUTES);
        if(notification == null) continue;
        final var planId = new PlanId(notification.planId());
        final var datasetId = notification.datasetId();

        // Register as early as possible to avoid potentially missing a canceled signal
        canceledListener.register(new DatasetId(datasetId));

        final Optional<ResultsProtocol.OwnerRole> owner = stores.results().claim(planId, datasetId);
        if (owner.isEmpty()) {
          canceledListener.unregister();
          continue;
        }

        final var revisionData = new PostgresPlanRevisionData(
            notification.modelRevision(),
            notification.planRevision(),
            notification.simulationRevision(),
            notification.simulationTemplateRevision());
        final ResultsProtocol.WriterRole writer = owner.get();
        try(final var reporter = ThreadedReporter.spawn("Reporter", new PostgresReporter(hikariDataSource, datasetId))) {
          simulationAgent.simulate(
              planId,
              revisionData,
              writer,
              canceledListener,
              reporter);
        } catch (final Throwable ex) {
          ex.printStackTrace(System.err);
          writer.failWith(b -> b
              .type("UNEXPECTED_SIMULATION_EXCEPTION")
              .message("Something went wrong while simulating")
              .trace(ex));
        }
        finally {
          canceledListener.unregister();
        }
      }
    } finally {
      // Kill the listening thread
      listenThread.interrupt();
    }
  }

  private static String getEnv(final String key, final String fallback){
    final var env = System.getenv(key);
    return env == null ? fallback : env;
  }

  private static WorkerAppConfiguration loadConfiguration() {
    return new WorkerAppConfiguration(
        Path.of(getEnv("MERLIN_WORKER_LOCAL_STORE", "/usr/src/app/merlin_file_store")),
        new PostgresStore(getEnv("AERIE_DB_HOST", "postgres"),
                          getEnv("MERLIN_DB_USER", ""),
                          Integer.parseInt(getEnv("AERIE_DB_PORT", "5432")),
                          getEnv("MERLIN_DB_PASSWORD", ""),
                          "aerie"),
        Integer.parseInt(getEnv("SIMULATION_PROGRESS_POLL_PERIOD_MILLIS", "5000")),
        Instant.parse(getEnv("UNTRUE_PLAN_START", ""))
    );
  }
}
