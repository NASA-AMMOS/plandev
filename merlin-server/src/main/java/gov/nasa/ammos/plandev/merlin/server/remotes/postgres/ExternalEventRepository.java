package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.plandev.procedural.timeline.payloads.ExternalSource;
import gov.nasa.ammos.plandev.merlin.server.http.InvalidJsonEntityException;
import gov.nasa.ammos.plandev.merlin.server.models.PlanId;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/*package-local*/ final class ExternalEventRepository {
  static Map<String, List<ExternalEvent>> getExternalEvents(
    final Connection connection,
    final PlanId planId,
    final Instant horizonStart
  ) throws SQLException, InvalidJsonEntityException
  {
    try (final var getPlanExternalEventsAction = new GetPlanExternalEventsAction(connection)) {
        Map<String, ExternalSource> sourceMap = getExternalSources(connection, planId);
        return getPlanExternalEventsAction.get(planId, horizonStart, sourceMap);
    }
  }

  static Map<String, ExternalSource> getExternalSources(
      final Connection connection,
      final PlanId planId
  ) throws SQLException, InvalidJsonEntityException {
    try (final var getExternalSourcesMapAction = new GetExternalSourcesMapAction(connection)) {
      return getExternalSourcesMapAction.get(planId);
    }
  }
}
