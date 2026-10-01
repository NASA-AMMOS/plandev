package gov.nasa.ammos.plandev.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Manages the test database.
 */
@SuppressWarnings("SqlSourceToSinkFlow")
public class DatabaseTestHelper {
  private final Connection connection;
  private final HikariDataSource hikariDataSource;

  private final String dbName;
  private final String appName;
  private final File initSqlScriptFile = new File("../deployment/postgres-init-db/sql/init.sql");

  // get database connection details from env file
  private static final String postgresHost = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
  private static final String postgresPort = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
  private static final String postgresUsername = getEnv("POSTGRES_USER");
  private static final String postgresPassword = getEnv("POSTGRES_PASSWORD");
  private static final String plandevUsername = getEnv("PLANDEV_USERNAME");
  private static final String plandevPassword = getEnv("PLANDEV_PASSWORD");

  public DatabaseTestHelper(String dbName, String appName) throws SQLException, IOException, InterruptedException {
    this.dbName = dbName;
    this.appName = appName;
    this.hikariDataSource = startDatabase();
    this.connection = hikariDataSource.getConnection();
  }

  /**
   * Sets up the test database
   */
  private HikariDataSource startDatabase() throws IOException, InterruptedException {
    // Create test database and grant privileges as postgres user
    {
      final var pb = new ProcessBuilder("psql",
                                        "postgresql://" + postgresUsername + ":" + postgresPassword
                                        + "@" + postgresHost + ":" + postgresPort + "/postgres",
                                        "-v", "ON_ERROR_STOP=1",
                                        "-c", "CREATE DATABASE " + dbName + ";",
                                        "-c", "GRANT ALL PRIVILEGES ON DATABASE " + dbName + " TO "+plandevUsername+";"
      );

      runProcess(
          pb,
          "Failed to create test Postgres database at %s:%s as user %s - " +
          "ensure PlanDev's Postgres container is available on port %s " +
          "and you are not running any other local instances of Postgres"
              .formatted(postgresHost, postgresPort, postgresUsername)
      );
    }

    // Grant table privileges to aerie user for the tests
    // (Apparently, the previous privileges are insufficient on their own)
    // and run the db init script
    {
      final var pb = new ProcessBuilder("psql",
                                        "postgresql://" + plandevUsername + ":" + plandevPassword
                                        + "@" + postgresHost + ":" + postgresPort + "/" + dbName,
                                        "-v", "ON_ERROR_STOP=1",
                                        "-v", "dbName=" + dbName,
                                        "-v", "aerie_user=" + plandevUsername,
                                        "-v", "gateway_user=" + plandevUsername,
                                        "-v", "merlin_user=" + plandevUsername,
                                        "-v", "scheduler_user=" + plandevUsername,
                                        "-v", "sequencing_user=" + plandevUsername,
                                        "-c", "ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO "+plandevUsername+";",
                                        "-c", "\\ir %s".formatted(initSqlScriptFile.getAbsolutePath())
      );

      runProcess(
          pb,
          "Failed to initialize test database %s at %s:%s".formatted(dbName, postgresHost, postgresPort)
      );
    }

    final var hikariConfig = new HikariConfig();

    hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
    hikariConfig.addDataSourceProperty("serverName", postgresHost);
    hikariConfig.addDataSourceProperty("portNumber", postgresPort);
    hikariConfig.addDataSourceProperty("databaseName", dbName);
    hikariConfig.addDataSourceProperty("applicationName", appName);

    hikariConfig.setUsername(plandevUsername);
    hikariConfig.setPassword(plandevPassword);

    hikariConfig.setConnectionInitSql("set time zone 'UTC'");

    return new HikariDataSource(hikariConfig);
  }

  /**
   * Tears down the test database
   */
  public void close() throws SQLException, IOException, InterruptedException {
    Assumptions.assumeTrue(connection != null);
    connection.close();

    // Clear out all data from the database on test conclusion
    // This is done WITH (FORCE) so there aren't issues with trying
    // to drop a database while there are connected sessions from
    // dev tools
    final var pb = new ProcessBuilder("psql",
                                      "postgresql://" + postgresUsername + ":" + postgresPassword
                                      + "@" + postgresHost + ":" + postgresPort + "/postgres",
                                      "-v", "ON_ERROR_STOP=1",
                                      "-c", "DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE);"
    );

    runProcess(
        pb,
        "Failed to clean up Postgres database %s after test run".formatted(dbName)
    );

    connection.close();
    hikariDataSource.close();
  }

  public Connection connection() {
    return connection;
  }

  private static String getEnv(final String key) {
    final var env = System.getenv(key);
    return env == null ? Assertions.fail("Could not find envvar: "+key) : env;
  }

  /**
   * Run a process from a ProcessBuilder, capture the output,
   * and throw an exception if it exits with an error code
   */
  private static void runProcess(
      final ProcessBuilder processBuilder,
      final String failureMessage
  ) throws IOException, InterruptedException {
    processBuilder.redirectErrorStream(true);

    final var process = processBuilder.start();
    final var output = new String(
        process.getInputStream().readAllBytes(),
        StandardCharsets.UTF_8
    );
    final var exitCode = process.waitFor();
    process.destroy();

    if (exitCode != 0) {
      throw new IllegalStateException("%s%n%s".formatted(failureMessage, output));
    }
  }

  public void clearTable(@Language(value="SQL", prefix="SELECT * FROM ") String table) throws SQLException {
    try (final var statement = connection.createStatement()) {
      statement.executeUpdate("TRUNCATE " + table + " CASCADE;");
    }
  }

  public void clearSchema(@Language(value="SQL", prefix="DROP SCHEMA ") String schema) throws SQLException {
    try (final var statement = connection.createStatement()) {
      final var res = statement.executeQuery(
          //language=sql
          """
          select tablename from pg_tables where schemaname = '%s';
          """.formatted(schema));
      while(res.next()) {
        clearTable(schema+"."+res.getString("tablename"));
      }
    }
  }
}
