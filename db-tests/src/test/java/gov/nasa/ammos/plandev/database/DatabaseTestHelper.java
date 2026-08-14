package gov.nasa.jpl.aerie.database;

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
    // Load database admin credentials from the environment
    final var aerieUsername = getEnv("AERIE_USERNAME");
    final var aeriePassword = getEnv("AERIE_PASSWORD");

    final var postgresUsername = getEnv("POSTGRES_USER");
    final var postgresPassword = getEnv("POSTGRES_PASSWORD");

    // Create test database and grant privileges
    {
      final var pb = new ProcessBuilder("psql",
                                        "postgresql://"+postgresUsername+":"+postgresPassword+"@localhost:5432/postgres",
                                        "-v", "ON_ERROR_STOP=1",
                                        "-c", "CREATE DATABASE " + dbName + ";",
                                        "-c", "GRANT ALL PRIVILEGES ON DATABASE " + dbName + " TO "+aerieUsername+";"
      );
      final var proc = pb.start();

      // Handle the case where we cannot connect to postgres by skipping the tests
      final var errors = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      Assumptions.assumeFalse(
          (  errors.contains("Connection refused")
          || errors.contains("role \""+postgresUsername+"\" does not exist")));
      proc.waitFor();
      proc.destroy();
    }

    // Grant table privileges to aerie user for the tests
    // Apparently, the previous privileges are insufficient on their own
    {
      final var pb = new ProcessBuilder("psql",
                                        "postgresql://"+aerieUsername+":"+aeriePassword+"@localhost:5432/" + dbName,
                                        "-v", "ON_ERROR_STOP=1",
                                        "-c", "ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO "+aerieUsername+";",
                                        "-c", "\\ir %s".formatted(initSqlScriptFile.getAbsolutePath())
      );

      pb.redirectError(ProcessBuilder.Redirect.INHERIT);
      final var proc = pb.start();
      proc.waitFor();
      proc.destroy();
    }

    final var hikariConfig = new HikariConfig();

    hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
    hikariConfig.addDataSourceProperty("serverName", "localhost");
    hikariConfig.addDataSourceProperty("portNumber", "5432");
    hikariConfig.addDataSourceProperty("databaseName", dbName);
    hikariConfig.addDataSourceProperty("applicationName", appName);

    hikariConfig.setUsername(aerieUsername);
    hikariConfig.setPassword(aeriePassword);

    hikariConfig.setConnectionInitSql("set time zone 'UTC'");

    return new HikariDataSource(hikariConfig);
  }

  /**
   * Tears down the test database
   */
  public void close() throws SQLException, IOException, InterruptedException {
    Assumptions.assumeTrue(connection != null);
    connection.close();

    // Grab postgres credentials from environment
    final var postgresUsername = getEnv("POSTGRES_USER");
    final var postgresPassword = getEnv("POSTGRES_PASSWORD");

    // Clear out all data from the database on test conclusion
    // This is done WITH (FORCE) so there aren't issues with trying
    // to drop a database while there are connected sessions from
    // dev tools
    final var pb = new ProcessBuilder("psql",
                                      "postgresql://"+postgresUsername+":"+postgresPassword+"@localhost:5432/postgres",
                                      "-v", "ON_ERROR_STOP=1",
                                      "-c", "DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE);"
    );

    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
    final var proc = pb.start();
    proc.waitFor();
    proc.destroy();
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
