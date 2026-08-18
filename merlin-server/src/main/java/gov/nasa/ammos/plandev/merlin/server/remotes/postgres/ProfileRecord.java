package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

public record ProfileRecord(
    long id,
    long datasetId,
    String name,
    Pair<String, ValueSchema> type,
    Duration duration
) {}
