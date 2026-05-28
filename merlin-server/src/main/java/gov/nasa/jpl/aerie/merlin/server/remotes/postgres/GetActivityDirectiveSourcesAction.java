package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.ActivitySource;
import gov.nasa.jpl.aerie.types.ResourceActivitySource;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetActivityDirectiveSourcesAction implements AutoCloseable {

  // TODO: need to aggregate across tables
  private static final @Language("SQL") String sql = """
    select
      scheduled_directive_id,
      array_agg(row(referenced_resource_name, referenced_resource_model_id)) as sources
    from merlin.directive_source_is_resource_type
    where scheduled_plan_id = ?
    group by scheduled_directive_id
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
        var currentSources = results.getArray("sources");
        var sourceList = new ArrayList<ActivitySource<?>>(); // TODO: generalize
        for (var sourceString : (Object[]) currentSources.getArray()) {
          // "(orbitNumber,1)"
          var split = sourceString
              .toString()
              .replace("(", "")
              .replace(")", "")
              .split(",");
          var resourceName = split[0];

          sourceList.add(new ResourceActivitySource(resourceName));
        }

        sourceMap.put(id, sourceList);
      }
    }

    return sourceMap;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
