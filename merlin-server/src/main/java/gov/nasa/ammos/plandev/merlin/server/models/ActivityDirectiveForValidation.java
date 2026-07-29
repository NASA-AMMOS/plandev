package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.types.ActivityDirectiveId;
import gov.nasa.ammos.plandev.types.SerializedActivity;

import java.sql.Timestamp;

public record ActivityDirectiveForValidation
(
    ActivityDirectiveId id,
    PlanId planId,
    Timestamp argumentsModifiedTime,
    SerializedActivity activity
) { }
