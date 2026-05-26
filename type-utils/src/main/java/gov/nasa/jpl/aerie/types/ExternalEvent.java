package gov.nasa.jpl.aerie.types;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.List;
import java.util.Map;

public record ExternalEvent(
    String key,
    String external_event_type,
    String derivation_group_name,
    String source_key,
    String source_created_at
) {
}
