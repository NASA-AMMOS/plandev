package gov.nasa.ammos.plandev.scheduler.worker.postgres;

public record PostgresSchedulingRequestNotificationPayload(
    long specificationRevision,
    long planRevision,
    long specificationId,
    long analysisId
) { }
