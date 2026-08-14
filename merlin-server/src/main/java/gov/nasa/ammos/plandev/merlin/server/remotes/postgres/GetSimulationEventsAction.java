package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.lang3.tuple.Pair;
import org.intellij.lang.annotations.Language;

import javax.json.Json;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.parseOffset;

/*package-local*/ final class GetSimulationEventsAction implements AutoCloseable {
  @Language("SQL") private final String sql = """
        select
          e.real_time,
          e.transaction_index,
          e.causal_time,
          e.topic_index,
          e.value,
          e.span_id
        from merlin.event as e
        where
          e.dataset_id = ?
      """;

  private final PreparedStatement statement;

  public GetSimulationEventsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(this.sql);
  }

  public SortedMap<Duration, List<EventGraph<EventRecord>>> get(final long datasetId) throws SQLException
  {
    this.statement.setLong(1, datasetId);
    final var resultSet = this.statement.executeQuery();

    final var transactionsByTimePoint = readResultSet(resultSet);

    final var eventPoints = new TreeMap<Duration, List<EventGraph<EventRecord>>>();
    transactionsByTimePoint.forEach((time, transactions) -> {
      transactions.forEach(($, value) -> {
        try {
          eventPoints
              .computeIfAbsent(time, x -> new ArrayList<>())
              .add(EventGraphUnflattener.unflatten(value));
        } catch (final EventGraphUnflattener.InvalidTagException e) {
          throw new Error("Failed to unflatten EventGraph due to invalid tag at time point " + time, e);
        }
      });
    });
    return eventPoints;
  }

  private static Map<Duration, SortedMap<Integer, List<Pair<String, EventRecord>>>>
  readResultSet(final ResultSet resultSet)
  throws SQLException {
    final var nodesByTimePoint = new HashMap<Duration, SortedMap<Integer, List<Pair<String, EventRecord>>>>();
    while (resultSet.next()) {
      final var timePoint = parseOffset(resultSet, 1);
      final var transactionIndex = resultSet.getInt(2);
      final var causalTime = resultSet.getString(3);
      final var topicIndex = resultSet.getInt(4);
      final var serializedValue = parseSerializedValue(resultSet.getString(5));
      final Optional<Long> spanId  = resultSet.getObject(6) == null ? Optional.empty() : Optional.of(
            Long.valueOf(resultSet.getLong(6)));

      nodesByTimePoint
          .computeIfAbsent(timePoint, x -> new TreeMap<>())
          .computeIfAbsent(transactionIndex, x -> new ArrayList<>())
          .add(Pair.of(
                   causalTime,
                   new EventRecord(
                       topicIndex,
                       spanId,
                       serializedValue
                   )
               )
          );
    }
    return nodesByTimePoint;
  }

  private static SerializedValue parseSerializedValue(final String value) {
    final SerializedValue serializedValue;
    try (
        final var serializedValueReader = Json.createReader(new StringReader(value))
    ) {
      serializedValue = serializedValueP
          .parse(serializedValueReader.readValue())
          .getSuccessOrThrow();
    }
    return serializedValue;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
