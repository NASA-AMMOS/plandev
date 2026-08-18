package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/*package-local*/ record SpanRecord(
    String type,
    Instant start,
    Optional<Duration> duration,
    Optional<Long> parentId,
    List<Long> childIds,
    ActivityAttributesRecord attributes
) {}
