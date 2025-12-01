package gov.nasa.jpl.plandev.scheduler.server.remotes.postgres;

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.plandev.types.Timestamp;

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
