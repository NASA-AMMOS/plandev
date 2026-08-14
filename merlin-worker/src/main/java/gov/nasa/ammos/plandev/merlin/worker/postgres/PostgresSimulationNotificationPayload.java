package gov.nasa.jpl.aerie.merlin.worker.postgres;

import java.util.Optional;

public record PostgresSimulationNotificationPayload(
    long modelRevision,
    long planRevision,
    long simulationRevision,
    Optional<Long> simulationTemplateRevision,
    long planId,
    long datasetId,
    long simulationId
) {}
