package gov.nasa.ammos.plandev.merlin.driver.resources;

import gov.nasa.ammos.plandev.merlin.protocol.types.RealDynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;

import java.util.Map;

public record ResourceProfiles(
      Map<String, ResourceProfile<RealDynamics>> realProfiles,
      Map<String, ResourceProfile<SerializedValue>> discreteProfiles
) {}
