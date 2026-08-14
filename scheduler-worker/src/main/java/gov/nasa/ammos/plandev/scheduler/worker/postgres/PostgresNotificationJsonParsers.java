package gov.nasa.jpl.aerie.scheduler.worker.postgres;

import static gov.nasa.jpl.aerie.json.BasicParsers.longP;
import static gov.nasa.jpl.aerie.json.BasicParsers.productP;
import static gov.nasa.jpl.aerie.json.Uncurry.tuple;
import static gov.nasa.jpl.aerie.json.Uncurry.untuple;
import gov.nasa.jpl.aerie.json.JsonParser;

public final class PostgresNotificationJsonParsers {

  public static final JsonParser<PostgresSchedulingRequestNotificationPayload> postgresSchedulingRequestNotificationP
      = productP
      . field("specification_revision", longP)
      . field("plan_revision", longP)
      . field("specification_id", longP)
      . field("analysis_id", longP)
      . map(
          untuple(PostgresSchedulingRequestNotificationPayload::new),
          $ -> tuple($.specificationRevision(),
                     $.planRevision(),
                     $.specificationId(),
                     $.analysisId()));
}
