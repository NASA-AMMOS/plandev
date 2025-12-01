package gov.nasa.jpl.plandev.merlin.server.models;

import gov.nasa.jpl.plandev.types.ActivityDirectiveId;
import gov.nasa.jpl.plandev.types.SerializedActivity;

import java.sql.Timestamp;

public record ActivityDirectiveForValidation
(
    ActivityDirectiveId id,
    PlanId planId,
    Timestamp argumentsModifiedTime,
    SerializedActivity activity
) { }
