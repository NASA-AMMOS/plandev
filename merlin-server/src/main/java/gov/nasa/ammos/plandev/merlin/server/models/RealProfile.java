package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.List;
import java.util.Optional;

public record RealProfile(
    ValueSchema schema,
    List<ProfileSegment<Optional<RealDynamics>>> segments) {}
