package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.SimulationFailure;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.http.MerlinParsers;
import gov.nasa.jpl.aerie.merlin.server.http.ResponseSerializers;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import javax.json.Json;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;

public final class PreparedStatements {
  private PreparedStatements() {}

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd HH:mm:ss")
          .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
          .appendOffset("+HH:mm:ss", "+00")
          .toFormatter();

  private static final @Language("SQL") String intervalStylePrefix = "set intervalstyle = ";

  /** Serialization format for postgres intervals (what we call Durations) */
  public enum PGIntervalStyle {
    ISO8601("iso_8601"),
    Postgres("postgres"),
    PostgresVerbose("postgres_verbose"),
    SqlStandard("sql_standard");

    private final String sqlName;

    PGIntervalStyle(final String sqlName) {
      this.sqlName = sqlName;
    }

    @Override
    public String toString() {
      return sqlName;
    }
  }

  /**
   * Sets the serialization format for postgres intervals.
   *
   * Call this before any statement that requires intervals to be serialized.
   */
  public static void setIntervalStyle(final Connection connection, final PGIntervalStyle style) throws SQLException {
    final var prepared = connection.prepareStatement(intervalStylePrefix + "'" + style + "';");
    prepared.execute();
  }

  public static void setTimestamp(final PreparedStatement statement, final int parameter, final Timestamp argument)
  throws SQLException {
    statement.setString(parameter, TIMESTAMP_FORMAT.format(argument.time()));
  }

  public static void setDuration(final PreparedStatement statement, final int parameter, final Duration argument) throws SQLException {
    final var micros = argument.in(Duration.MICROSECONDS);
    statement.setString(parameter, "PT%d.%06dS".formatted(micros / 1_000_000, micros % 1_000_000));
  }

  public static void setParameters(final PreparedStatement statement, final int parameter, final List<Parameter> parameters)
  throws SQLException {
    statement.setString(parameter, ResponseSerializers.serializeParameters(parameters).toString());
  }

  public static void setRequiredParameters(final PreparedStatement statement, final int parameter, final List<String> requiredParameters)
  throws SQLException {
    statement.setString(parameter, ResponseSerializers.serializeStringList(requiredParameters).toString());
  }

  public static void setValidationResponse(
      final PreparedStatement statement,
      final int parameter,
      final MissionModelService.BulkArgumentValidationResponse response
  ) throws SQLException {
    statement.setString(parameter, ResponseSerializers.serializeBulkArgumentValidationResponse(response).toString());
  }

  public static void setFailureReason(final PreparedStatement statement, final int parameter, final SimulationFailure reason)
  throws SQLException
  {
    statement.setString(parameter, reason == null ?
        null :
        MerlinParsers.simulationFailureP.unparse(reason).toString());
  }

  public static Optional<SimulationFailure> getFailureReason(final ResultSet results, final int column)
  throws SQLException
  {
    final var failureJson = results.getString(column);
    return failureJson == null || failureJson.isBlank() ?
        Optional.empty() :
        Optional.of(PreparedStatements.deserializeScheduleFailure(results.getString(column)));
  }

  private static SimulationFailure deserializeScheduleFailure(final String failureJson) {
    try (final var reader = Json.createReader(new ByteArrayInputStream(failureJson.getBytes(StandardCharsets.UTF_8)))) {
      return MerlinParsers.simulationFailureP.parse(reader.readValue()).getSuccessOrThrow();
    }
  }
}
