package gov.nasa.ammos.plandev.merlin.driver.engine;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import java.util.Optional;

public record EventRecord(int topicId, Optional<Long> spanId, SerializedValue value) {}
