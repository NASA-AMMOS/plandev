package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.ActivitySource;
import gov.nasa.jpl.aerie.types.DirectiveActivitySource;
import gov.nasa.jpl.aerie.types.ExternalEvent;
import gov.nasa.jpl.aerie.types.ExternalEventActivitySource;
import gov.nasa.jpl.aerie.types.ResourceActivitySource;
import org.intellij.lang.annotations.Language;

import javax.json.Json;
import javax.json.JsonArray;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*package-local*/ final class GetActivityDirectiveSourcesAction implements AutoCloseable {

  // TODO: use view instead
  private static final @Language("SQL") String sql = """
      select
        scheduled_directive_id,
        scheduled_plan_id,
        sources
      from merlin.scheduling_sources
      where scheduled_plan_id = ?;
    """;

  private final PreparedStatement statement;

  public GetActivityDirectiveSourcesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<Long, List<ActivitySource<?>>> get(final long planId) throws SQLException {
    this.statement.setLong(1, planId);

    Map<Long, List<ActivitySource<?>>> sourceMap = new HashMap<>();
    try (final var results = this.statement.executeQuery()) {
      while (results.next()) {
        var id = results.getLong("scheduled_directive_id");

        // I do not like the java.sql library.
        JsonArray sources = Json.createReader(new StringReader(results.getObject("sources").toString())).readArray();
        var sourceList = new ArrayList<ActivitySource<?>>(); // TODO: generalize
        for (var source : sources) {
          String type = source.asJsonObject().getString("type");

          if (type.contains("activity")) {
            long directiveSourceId = source.asJsonObject().getInt("value");
            sourceList.add(
                new DirectiveActivitySource(
                  null,
                  directiveSourceId
                )
            );
          }
          else if (type.contains("resource")) {
            String resourceName = source.asJsonObject().getString("value");
            sourceList.add(new ResourceActivitySource(resourceName));
          }
          else {
            var eventDetails = source.asJsonObject().getJsonObject("value");
            String key = eventDetails.getString("referenced_event_key");
            String event_type = eventDetails.getString("referenced_event_type");
            String source_key = eventDetails.getString("referenced_event_source_key");
            String source_created_at = eventDetails.getString("referenced_event_source_created_at");
            String derivation_group = eventDetails.getString("referenced_event_derivation_group");
            sourceList.add(new ExternalEventActivitySource(
              new ExternalEvent(
                  key,
                  event_type,
                  source_key,
                  source_created_at,
                  derivation_group
              )
            ));
          }
          sourceMap.put(id, sourceList);
        }
      }
    }

    return sourceMap;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
