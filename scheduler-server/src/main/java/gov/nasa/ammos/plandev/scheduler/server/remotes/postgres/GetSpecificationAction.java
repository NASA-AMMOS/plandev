package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import javax.json.Json;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/*package-local*/ final class GetSpecificationAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
    select
      spec.revision,
      spec.plan_id,
      spec.plan_revision,
      to_char(spec.horizon_start, 'YYYY-DDD"T"HH24:MI:SS.FF6') as horizon_start,
      to_char(spec.horizon_end, 'YYYY-DDD"T"HH24:MI:SS.FF6') as horizon_end,
      spec.simulation_arguments,
      spec.analysis_only
    from scheduler.scheduling_specification as spec
      where spec.id = ?
    """;

  private final PreparedStatement statement;

  public GetSpecificationAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Optional<SpecificationRecord> get(
      final long specificationId
  ) throws SQLException {
    this.statement.setLong(1, specificationId);

    try (final var resultSet = this.statement.executeQuery()) {
      if (!resultSet.next()) return Optional.empty();

      final var revision = resultSet.getLong("revision");
      final var planId = resultSet.getLong("plan_id");
      final var planRevision = resultSet.getLong("plan_revision");
      final var horizonStart = Timestamp.fromString(resultSet.getString("horizon_start"));
      final var horizonEnd = Timestamp.fromString(resultSet.getString("horizon_end"));
      final var arguments = parseSimulationArguments(resultSet.getCharacterStream("simulation_arguments"));
      final var analysisOnly = resultSet.getBoolean("analysis_only");

      return Optional.of(new SpecificationRecord(
          specificationId,
          revision,
          planId,
          planRevision,
          horizonStart,
          horizonEnd,
          arguments,
          analysisOnly
      ));
    }
  }

  private Map<String, SerializedValue> parseSimulationArguments(final Reader stream) {
    try (final var reader = Json.createReader(stream)) {
      final var json = reader.readValue();
      return PostgresParsers.simulationArgumentsP
          .parse(json)
          .getSuccessOrThrow(
              failureReason -> new Error("Corrupt simulation arguments cannot be parsed: " + failureReason.reason())
          );
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
