package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.merlin.driver.Reporter;
import gov.nasa.jpl.aerie.merlin.driver.engine.EventRecord;
import gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.ActivityAttributesRecord;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphFlattener;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PreparedStatements;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityAttributesP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PreparedStatements.setTimestamp;

public final class PostgresReporter implements Reporter, AutoCloseable {
  private final DataSource dataSource;
  private final long datasetId;
  private final Map<String, Long> profileIds;
  private final Map<String, Long> spanIds;
  private Duration simulationExtent = Duration.ZERO;
  private Duration lastEventsMessage = Duration.SECOND.negate();
  private int commitCounter = -1;
  private Map<String, Message.UpdateSpan> openSpans;

  private final Reporter delegate; // TODO this is a temporary hack

  public PostgresReporter(DataSource dataSource, long datasetId, Reporter delegate) {
    this.dataSource = dataSource;
    this.datasetId = datasetId;
    this.profileIds = new LinkedHashMap<>();
    this.spanIds = new LinkedHashMap<>();
    this.openSpans = new LinkedHashMap<>();
    this.delegate = delegate;
  }

  @Override
  public void report(final Message message) {
    delegate.report(message);
    switch (message) {
      case Message.AdvanceTime m -> {
        this.simulationExtent = m.startOffset();
      }

      case Message.UpdateSpan m -> {
        if (m.duration().isEmpty()) {
          openSpans.put(m.spanId(), m);
        } else {
          openSpans.remove(m.spanId());
          insertSpan(m);
        }
      }


      case Message.DeclareProfile m -> {
        try (var connection = dataSource.getConnection()) {
          var statement = connection.prepareStatement(
              """
                    insert into merlin.profile (dataset_id, name, type, duration)
                    values (?, ?, ?::jsonb, ?::interval)
                  """, Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1, datasetId);
          statement.setString(2, m.profileName());
          final JsonValue valueSchemaJson = valueSchemaP.unparse(m.schema());

          final JsonObject items = valueSchemaJson.asJsonObject().getJsonObject("items");
          boolean isRealSchema = false;
          if (items != null) {
            isRealSchema = items.keySet().equals(Set.of("rate", "initial"));
          }

          statement.setString(
              3, Json.createObjectBuilder()
                     .add("type", isRealSchema ? "real" : "discrete")
                     .add("schema", valueSchemaJson)
                     .build()
                     .toString());
          PreparedStatements.setDuration(statement, 4, simulationExtent);

          statement.execute();
          final var resultSet = statement.getGeneratedKeys();
          resultSet.next();
          final long id = resultSet.getLong(1);
          profileIds.put(m.profileName(), id);
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }

      case Message.UpdateProfile m -> {
        try (var connection = dataSource.getConnection()) {
          var statement = connection.prepareStatement("""
                                                            insert into merlin.profile_segment (dataset_id, profile_id, start_offset, dynamics, is_gap)
                                                            values (?, ?, ?::interval, ?::json, ?)
                                                          """);
          statement.setLong(1, datasetId);
          statement.setLong(2, profileIds.get(m.profileName()));
          PreparedStatements.setDuration(statement, 3, m.startOffset());
          statement.setString(4, new SerializedValueJsonParser().unparse(m.value()).toString());
          statement.setBoolean(5, false);
          statement.execute();
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }

      case Message.DeclareTopic m -> {
        try (var connection = dataSource.getConnection()) {
          var statement = connection.prepareStatement("""
                                                            insert into merlin.topic (dataset_id, topic_index, name, value_schema)
                                                            values (?, ?, ?, ?::jsonb)
                                                          """);
          statement.setLong(1, datasetId);
          statement.setLong(2, m.topicId());
          statement.setString(3, m.topicName());
          statement.setString(4, valueSchemaP.unparse(m.schema()).toString());
          statement.execute();
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }
      case Message.Events m -> {
        try (var connection = dataSource.getConnection()) {
          var statement = connection.prepareStatement("""
                                                            insert into merlin.event (dataset_id, real_time, transaction_index, causal_time, topic_index, value, span_id)
                                                            values (?, ?::interval, ?, ?, ?, ?::jsonb,?)
                                                          """);

          if (lastEventsMessage != m.startOffset()) {
            commitCounter = 0;
          } else {
            commitCounter += 1;
          }
          lastEventsMessage = m.startOffset();

          for (var entry : EventGraphFlattener.flatten(m.events())) {
            final var denseTime = entry.getLeft();
            final EventRecord event = entry.getRight();

            statement.setLong(1, datasetId);
            PreparedStatements.setDuration(statement, 2, m.startOffset());
            statement.setInt(3, commitCounter);
            statement.setString(4, denseTime);
            statement.setInt(5, event.topicId());
            statement.setString(6, serializedValueP.unparse(event.value()).toString());
            statement.setObject(7, event.spanId().orElse(null), Types.INTEGER);
            statement.addBatch();
          }

          statement.executeBatch();
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }

      }

      case Message.Error m -> {
        // TODO update reason
      }

      case Message.Finish m -> {
        // TODO
      }
    }
  }

  private void insertSpan(Message.UpdateSpan m) {
    this.spanIds.put(m.spanId(), (long) this.spanIds.size());

    try (var connection = dataSource.getConnection()) {
      var statement = connection.prepareStatement(
          """
                insert into merlin.span (span_id,dataset_id,parent_id, start_offset, duration, type, attributes)
                values (?,?,?, ?::interval, ?::interval, ?, ?::jsonb)
              """);
      statement.setLong(1, this.spanIds.get(m.spanId()));
      statement.setLong(2, datasetId);
//      if (m.parentSpanId().isPresent()) {
//        statement.setLong(3, act.parentId().get()); // TODO
//      } else {
        statement.setNull(3, Types.BIGINT);
//      }
      PreparedStatements.setDuration(statement, 4, m.startOffset());

      if (m.duration().isPresent()) {
        PreparedStatements.setDuration(statement, 5, m.duration().get());
      } else {
        statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
      }

      statement.setString(6, m.type());
      statement.setString(7, buildAttributes(m.directiveId(), m.payload().asMap().get(), Optional.empty()));

      statement.execute();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private String buildAttributes(
      final Optional<Long> directiveId,
      final Map<String, SerializedValue> arguments,
      final Optional<SerializedValue> returnValue)
  {
    return activityAttributesP.unparse(new ActivityAttributesRecord(directiveId, arguments, returnValue)).toString();
  }

  @Override
  public void close() {
    try (var connection = dataSource.getConnection()) {
      var statement = connection.prepareStatement("""
                                                        update merlin.profile
                                                        set duration = ?::interval
                                                        where dataset_id=?;
                                                      """);
      PreparedStatements.setDuration(statement, 1, this.simulationExtent);
      statement.setLong(2, datasetId);
      statement.execute();

      for (final Message.UpdateSpan updateSpan : openSpans.values()) {
        insertSpan(updateSpan);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
