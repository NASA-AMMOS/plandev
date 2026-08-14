package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import java.util.Optional;

public record EventRecord(int topicId, Optional<Long> spanId, SerializedValue value) {}
