package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.List;
import java.util.Optional;

public record DiscreteProfile(
    ValueSchema schema,
    List<ProfileSegment<Optional<SerializedValue>>> segments) {}
