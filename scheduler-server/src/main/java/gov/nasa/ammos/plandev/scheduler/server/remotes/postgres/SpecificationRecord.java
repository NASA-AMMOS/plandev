package gov.nasa.jpl.aerie.scheduler.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Timestamp;

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
