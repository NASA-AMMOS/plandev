package gov.nasa.jpl.plandev.merlin.worker.postgres;

import gov.nasa.jpl.plandev.json.JsonParser;

import static gov.nasa.jpl.plandev.json.BasicParsers.longP;
import static gov.nasa.jpl.plandev.json.BasicParsers.productP;
import static gov.nasa.jpl.plandev.json.Uncurry.tuple;
import static gov.nasa.jpl.plandev.json.Uncurry.untuple;

public final class PostgresNotificationJsonParsers {

  public static final JsonParser<PostgresSimulationNotificationPayload> postgresSimulationNotificationP
      = productP
      . field("model_revision", longP)
      . field("plan_revision", longP)
      . field("simulation_revision", longP)
      . optionalField("simulation_template_revision", longP)
      . field("plan_id", longP)
      . field("dataset_id", longP)
      . field("simulation_id", longP)
      . map(
          untuple(PostgresSimulationNotificationPayload::new),
          $ -> tuple($.modelRevision(),
                     $.planRevision(),
                     $.simulationRevision(),
                     $.simulationTemplateRevision(),
                     $.planId(),
                     $.datasetId(),
                     $.simulationId()));
}
