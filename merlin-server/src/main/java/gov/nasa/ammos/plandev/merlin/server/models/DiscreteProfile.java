package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.merlin.driver.engine.ProfileSegment;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;

import java.util.List;
import java.util.Optional;

public record DiscreteProfile(
    ValueSchema schema,
    List<ProfileSegment<Optional<SerializedValue>>> segments) {}
