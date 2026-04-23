package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.eventAttributesP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetExternalSourcesMapAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      select
        key,
        attributes,
        derivation_group_name
      from merlin.plan_derivation_group as pdg
      join merlin.external_source as s using (derivation_group_name)
      where pdg.plan_id = ?;
    """;

  private final PreparedStatement statement;

  public GetExternalSourcesMapAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<String, ExternalSource> get(final PlanId planId)
  throws SQLException, InvalidEntityException
  {
    final var result = new HashMap<String, ExternalSource>();
    this.statement.setLong(1, planId.id());
    final var resultSet = statement.executeQuery();

    while (resultSet.next()) {
      // get source key
      final String key = resultSet.getString("key");
      // get source attributes
      final var attributes = getJsonColumn(resultSet, "attributes", eventAttributesP)
          .getSuccessOrThrow(reason -> new InvalidEntityException(List.of(reason)));
      // get derivation group
      final String derivationGroup = resultSet.getString("derivation_group_name");

      // create source
      ExternalSource s = new ExternalSource(
          key,
          derivationGroup,
          attributes
      );

      if (!result.containsKey(key)) {
        result.put(key, s);
      }
    }
    return result;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
