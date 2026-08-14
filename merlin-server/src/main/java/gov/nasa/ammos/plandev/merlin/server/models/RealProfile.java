package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.merlin.driver.engine.ProfileSegment;
import gov.nasa.ammos.plandev.merlin.protocol.types.RealDynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;

import java.util.List;
import java.util.Optional;

public record RealProfile(
    ValueSchema schema,
    List<ProfileSegment<Optional<RealDynamics>>> segments) {}
