package gov.nasa.ammos.aerie.simulation.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SimulatedActivity(
        String type,
        Map<String, SerializedValue> arguments,
        Instant start,
        Duration duration,
        Long parentId, // nullable
        List<Long> childIds,
        Optional<Long> directiveId,
        SerializedValue computedAttributes
) {
}
