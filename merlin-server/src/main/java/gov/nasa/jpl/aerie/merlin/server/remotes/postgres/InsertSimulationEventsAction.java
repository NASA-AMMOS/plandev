package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.EventGraphFlattener;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.apache.commons.lang3.tuple.Pair;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PreparedStatements.setTimestamp;

/*package-local*/ final class InsertSimulationEventsAction implements AutoCloseable {
  @Language("SQL") private static final String sql = """
      insert into merlin.event (dataset_id, real_time, transaction_index, causal_time, topic_index, value, span_id)
      values (?, ?::timestamptz - ?::timestamptz, ?, ?, ?, ?::jsonb,?)
    """;

  private final PreparedStatement statement;

  public InsertSimulationEventsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(
      final long datasetId,
      final Map<Duration, List<EventGraph<EventRecord>>> eventPoints,
      final Timestamp simulationStart
  ) throws SQLException {
    for (final var eventPoint : eventPoints.entrySet()) {
      final var time = eventPoint.getKey();
      final var transactions = eventPoint.getValue();
      for (int transactionIndex = 0; transactionIndex < transactions.size(); transactionIndex++) {
        final var eventGraph = transactions.get(transactionIndex);
        final var flattenedEventGraph = EventGraphFlattener.flatten(eventGraph);
        batchInsertEventGraph(datasetId, time, transactionIndex, simulationStart, flattenedEventGraph, this.statement);
      }
    }
    this.statement.executeBatch();
  }

  private static void batchInsertEventGraph(
      final long datasetId,
      final Duration duration,
      final int transactionIndex,
      final Timestamp simulationStart,
      final List<Pair<String, EventRecord>> flattenedEventGraph,
      final PreparedStatement statement
  ) throws SQLException {
    for (final Pair<String, EventRecord> entry : flattenedEventGraph) {
      final var causalTime = entry.getLeft();
      final EventRecord event = entry.getRight();

      statement.setLong(1, datasetId);
      setTimestamp(statement, 2, simulationStart.plusMicros(duration.in(MICROSECONDS)));
      setTimestamp(statement, 3, simulationStart);
      statement.setInt(4, transactionIndex);
      statement.setString(5, causalTime);
      statement.setInt(6, event.topicId());
      statement.setString(7, serializedValueP.unparse(event.value()).toString());
      statement.setObject(8, event.spanId().orElse(null), Types.INTEGER);
      statement.addBatch();
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
