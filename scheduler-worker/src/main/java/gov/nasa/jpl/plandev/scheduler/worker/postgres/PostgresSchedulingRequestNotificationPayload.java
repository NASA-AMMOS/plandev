package gov.nasa.jpl.plandev.scheduler.worker.postgres;

public record PostgresSchedulingRequestNotificationPayload(
    long specificationRevision,
    long planRevision,
    long specificationId,
    long analysisId
) { }
