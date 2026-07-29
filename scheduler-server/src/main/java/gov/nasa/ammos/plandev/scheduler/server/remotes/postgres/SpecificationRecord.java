package gov.nasa.ammos.plandev.scheduler.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.types.Timestamp;

import java.util.Map;

public record SpecificationRecord(
    long id,
    long revision,
    long planId,
    long planRevision,
    Timestamp horizonStartTimestamp,
    Timestamp horizonEndTimestamp,
    Map<String, SerializedValue> simulationArguments,
    boolean analysisOnly
) {}
