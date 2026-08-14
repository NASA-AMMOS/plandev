package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import javax.json.Json;
import gov.nasa.jpl.aerie.scheduler.server.http.SchedulerParsers;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleFailure;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class PreparedStatements {
  private PreparedStatements() {}

  public static void setFailureReason(final PreparedStatement statement, final int parameter, final ScheduleFailure reason)
  throws SQLException
  {
    statement.setString(parameter, reason == null ?
        null :
        SchedulerParsers.scheduleFailureP.unparse(reason).toString());
  }

  public static Optional<ScheduleFailure> getFailureReason(final ResultSet results, final String columnLabel)
  throws SQLException
  {
    final var failureJson = results.getString(columnLabel);
    return failureJson == null || failureJson.isBlank() ?
        Optional.empty() :
        Optional.of(PreparedStatements.deserializeScheduleFailure(results.getString(columnLabel)));
  }

  private static ScheduleFailure deserializeScheduleFailure(final String failureJson) {
    try (final var reader = Json.createReader(new ByteArrayInputStream(failureJson.getBytes(StandardCharsets.UTF_8)))) {
      return SchedulerParsers.scheduleFailureP.parse(reader.readValue()).getSuccessOrThrow();
    }
  }

  public static Optional<Long> getDatasetId(final ResultSet results, final String columnLabel)
  throws SQLException
  {
    final var datasetId = results.getObject(columnLabel);
    return datasetId == null ?
        Optional.empty() :
        Optional.of(results.getLong(columnLabel));
  }
}
