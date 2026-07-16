package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonEntityException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.eventAttributesP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

/*package-local*/ final class GetPlanExternalEventsAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
      select
        ee.event_key,
        ee.event_type_name,
        ee.source_key,
        ee.derivation_group_name,
        ee.start_time,
        extract(epoch from ee.duration)*1e6 as duration_micros,
        ee.attributes
      from merlin.derived_events as ee
        join (
          select
            pdg.derivation_group_name,
            pdg.plan_id
          from merlin.plan_derivation_group as pdg
            ) pdg
              on pdg.derivation_group_name = ee.derivation_group_name
        where pdg.plan_id = ?;
    """;

  private final PreparedStatement statement;

  public GetPlanExternalEventsAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<String, List<ExternalEvent>> get(final PlanId planId, final Instant horizonStart, Map<String, ExternalSource> sources)
  throws SQLException, InvalidJsonEntityException
  {
    final var result = new HashMap<String, List<ExternalEvent>>();
    this.statement.setLong(1, planId.id());
    final var resultSet = statement.executeQuery();

    while (resultSet.next()) {
      // get start
      final var start = new Duration(
          horizonStart.until(resultSet.getTimestamp("start_time").toInstant(), ChronoUnit.MICROS)
      );
      // get end
      Duration duration = Duration.of(resultSet.getLong("duration_micros"), Duration.MICROSECOND);
      final var end = start.plus(duration);

      // get event attributes
      final var eventAttributes = getJsonColumn(resultSet, "attributes", eventAttributesP)
          .getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)));
      // get derivation group
      final String derivationGroup = resultSet.getString("derivation_group_name");
      // get event key
      final String key = resultSet.getString("event_key");
      // get event_type
      final String eventType = resultSet.getString("event_type_name");

      // retrieve source
      ExternalSource s = sources.get(resultSet.getString("source_key"));

      // create event
      ExternalEvent e = new ExternalEvent(
          key,
          eventType,
          s,
          eventAttributes,
          Interval.between(start, end)
      );
      if (result.containsKey(derivationGroup)) {
        result.get(derivationGroup).add(e);
      }
      else {
        result.put(derivationGroup, new ArrayList<>(List.of(e)));
      }
    }
    return result;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
