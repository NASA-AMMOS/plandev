package gov.nasa.ammos.plandev.scheduler.server.models;

import gov.nasa.ammos.plandev.merlin.driver.resources.ResourceProfile;
import gov.nasa.ammos.plandev.merlin.protocol.types.RealDynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;

import java.util.Map;

public record UnwrappedProfileSet(
    Map<String, ResourceProfile<RealDynamics>> realProfiles,
    Map<String, ResourceProfile<SerializedValue>> discreteProfiles
){}
