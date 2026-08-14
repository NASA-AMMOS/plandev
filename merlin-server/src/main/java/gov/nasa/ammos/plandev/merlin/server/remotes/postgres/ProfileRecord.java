package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

public record ProfileRecord(
    long id,
    long datasetId,
    String name,
    Pair<String, ValueSchema> type,
    Duration duration
) {}
