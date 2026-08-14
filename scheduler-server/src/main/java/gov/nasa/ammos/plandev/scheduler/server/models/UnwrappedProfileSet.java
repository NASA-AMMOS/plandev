package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;

public record UnwrappedProfileSet(
    Map<String, ResourceProfile<RealDynamics>> realProfiles,
    Map<String, ResourceProfile<SerializedValue>> discreteProfiles
){}
