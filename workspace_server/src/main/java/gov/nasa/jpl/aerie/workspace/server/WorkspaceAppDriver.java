package gov.nasa.jpl.aerie.workspace.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.workspace.server.config.AppConfiguration;
import gov.nasa.jpl.aerie.workspace.server.config.PostgresStore;
import gov.nasa.jpl.aerie.workspace.server.config.Store;
import gov.nasa.jpl.aerie.workspace.server.config.UnexpectedSubtypeError;
import gov.nasa.jpl.aerie.workspace.server.postgres.WorkspacePostgresRepository;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LowResourceMonitor;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.nio.file.Path;

public final class WorkspaceAppDriver {

  public static void main(final String[] args) {
    // Fetch application configuration properties.
    final var configuration = loadConfiguration();
    final var stores = loadStores(configuration);

    // Assemble the core non-web object graph.
    final var workspaceBindings = new WorkspaceBindings(stores.jwt, stores.workspace);
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

      // Configure multipart files
      config.jetty.multipartConfig.cacheDirectory(System.getProperty("java.io.tmpdir", "/tmp")); //where to write files that exceed the in memory limit
      config.jetty.multipartConfig.maxFileSize(10/*0*/, SizeUnit.MB); //the maximum individual file size allowed
      config.jetty.multipartConfig.maxInMemoryFileSize(10, SizeUnit.MB); //the maximum file size to handle in memory
      config.jetty.multipartConfig.maxTotalRequestSize(1, SizeUnit.GB); //the maximum size of the entire multipart request

      config.plugins.enableCors(cors -> cors.add(CorsPluginConfig::anyHost));
      config.plugins.register(workspaceBindings);
      config.jetty.server(() -> server);
    });

    // Start the HTTP server.
    javalin.start(9999);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      javalin.stop();
      connector.close();
    }));
  }

  private record Stores (JWTService jwt, WorkspaceService workspace) {}

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

      final var jwt = new JWTService(config.jwtSecret());
      final var workspace = new WorkspaceFileSystemService(new WorkspacePostgresRepository(config.workspacesFileStore(), hikariDataSource));
      return new Stores(jwt, workspace);
    } else {
      throw new UnexpectedSubtypeError(Store.class, store);
    }
  }

  private static String getEnv(final String key, final String fallback) {
    final var env = System.getenv(key);
    return env == null ? fallback : env;
  }

  private static AppConfiguration loadConfiguration() {
    final var logger = LoggerFactory.getLogger(WorkspaceAppDriver.class);
    final var secretString = getEnv("HASURA_GRAPHQL_JWT_SECRET", "");
    final JsonObject jwtSecret;
    if (secretString.isBlank()) {
      jwtSecret = JsonObject.EMPTY_JSON_OBJECT;
    } else {
      try(final var reader = Json.createReader(new StringReader(secretString))) {
        jwtSecret = reader.readObject();
      }
    }

    return new AppConfiguration(
        Integer.parseInt(getEnv("WORKSPACE_PORT", "28000")),
        logger.isDebugEnabled(),
        Path.of(getEnv("WORKSPACE_STORE", "/usr/src/ws")),
        jwtSecret,
        new PostgresStore(getEnv("AERIE_DB_HOST", "postgres"),
                          getEnv("SEQUENCING_DB_USER", ""),
                          Integer.parseInt(getEnv("AERIE_DB_PORT", "5432")),
                          getEnv("SEQUENCING_DB_PASSWORD", ""),
                          "aerie")
    );
  }
}
