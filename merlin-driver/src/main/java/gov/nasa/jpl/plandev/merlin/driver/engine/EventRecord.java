package gov.nasa.jpl.plandev.merlin.driver.engine;

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue;
import java.util.Optional;

public record EventRecord(int topicId, Optional<Long> spanId, SerializedValue value) {}
