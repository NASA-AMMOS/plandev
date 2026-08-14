package gov.nasa.jpl.aerie.scheduler.worker.postgres;

public record PostgresSchedulingRequestNotificationPayload(
    long specificationRevision,
    long planRevision,
    long specificationId,
    long analysisId
) { }
