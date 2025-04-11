package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalSource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.intellij.lang.annotations.Language;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.externalEventAttributesP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.parseOffset;

/*package-local*/ final class GetExternalEventsAction implements AutoCloseable {
  private static final @Language("SQL") String sql = """
    select
      source_key,
      event_type_name,
      event_key,
      duration,
      d.attributes as event_attributes,
      s.attributes as source_attributes,
      d.derivation_group_name,
      to_char(d.start_time, 'YYYY-DDD"T"HH24:MI:SS.FF6') as start_time,
      d.valid_at
    from merlin.derived_events d
      join merlin.external_source s
      on d.source_key = s.key AND d.derivation_group_name = s.derivation_group_name
    where d.derivation_group_name
    in (
      select derivation_group_name
      from merlin.plan_derivation_group
      where plan_id=%d
    )
    """;

  private final PreparedStatement statement;

  public GetExternalEventsAction(final Connection connection, final PlanId planId) throws SQLException {
    this.statement = connection.prepareStatement(sql.formatted(planId.id()));
  }

  public Map<String, List<ExternalEvent>> get(final Instant horizonStart) throws SQLException {
    try (final var results = this.statement.executeQuery()) {
      final var mappedResults = new HashMap<String, List<ExternalEvent>>();
      final var unorganized = new ArrayList<ExternalEvent>();
      while (results.next()) {
        // We use here more intricate formatting and parsers to ensure consistency in results regardless of how the
        //    database formats our result. This insulates against the rare case of Postgres returning ISO8601 formatted
        //    times, which can happen unpredictably.
        final var startInstant = Timestamp.fromString(results.getString("start_time")).toInstant();
        final var startDuration = new Duration(
            horizonStart.until(startInstant, ChronoUnit.MICROS)
        );
        final var duration = parseOffset(results, "duration");
        final var endDuration = startDuration.plus(duration);
        unorganized.add(new ExternalEvent(
            results.getString("event_key"),
            results.getString("event_type_name"),
            new ExternalSource(
                results.getString("source_key"),
                results.getString("derivation_group_name"),
                getJsonColumn(results, "source_attributes", externalEventAttributesP)
                    .getSuccessOrThrow($ -> new Error("Corrupt source attributes cannot be parsed: " + $.reason()))
            ),
            getJsonColumn(results, "event_attributes", externalEventAttributesP)
                .getSuccessOrThrow($ -> new Error("Corrupt source attributes cannot be parsed: " + $.reason())),
            Interval.between(startDuration, endDuration)
        ));
      }
      for (final var event: unorganized) {
        final var list = mappedResults.computeIfAbsent(event.source.derivationGroup, $ -> new ArrayList<>());
        list.add(event);
      }
      return mappedResults;
    }
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
