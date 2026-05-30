package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.ActivitySource;
import gov.nasa.jpl.aerie.types.DirectiveActivitySource;
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
      select\s
      	a.scheduled_directive_id,
      	array_agg(concat('a: ', text(a.referenced_directive_id)))\s
      		|| array_agg(concat('r: ', r.referenced_resource_name)) as sources
      from merlin.directive_source_is_activity as a
      join merlin.directive_source_is_resource_type as r
      on a.scheduled_directive_id = r.scheduled_directive_id
      where a.scheduled_plan_id = ? and r.referenced_resource_model_id = ?
      group by a.scheduled_directive_id;
    """;

  private final PreparedStatement statement;

  public GetActivityDirectiveSourcesAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<Long, List<ActivitySource<?>>> get(final long planId, final long modelId) throws SQLException {
    this.statement.setLong(1, planId);
    this.statement.setLong(2, modelId);

    Map<Long, List<ActivitySource<?>>> sourceMap = new HashMap<>();
    try (final var results = this.statement.executeQuery()) {
      while (results.next()) {
        var id = results.getLong("scheduled_directive_id");
        var currentSources = results.getArray("sources");
        var sourceList = new ArrayList<ActivitySource<?>>(); // TODO: generalize
        for (var source : (Object[]) currentSources.getArray()) {
          var sourceString = source.toString();

          // could be {"a: 14","r: orbitNumber"}, where first one is an activity directive, second is resource type
          if (sourceString.contains("a: ")) { // DirectiveActivitySource
            var directiveSourceId = Long.parseLong(sourceString.replace("a: ", ""));
            sourceList.add(
                new DirectiveActivitySource(
                  null,
                  directiveSourceId
                )
            );
          }
          else { // ResourceActivitySource
            var resourceName = sourceString.replace("r: ", "");
            sourceList.add(new ResourceActivitySource(resourceName));
          }
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
