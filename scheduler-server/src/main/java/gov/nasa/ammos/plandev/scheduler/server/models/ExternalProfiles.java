package gov.nasa.ammos.plandev.scheduler.server.models;

import gov.nasa.ammos.plandev.constraints.model.DiscreteProfile;
import gov.nasa.ammos.plandev.constraints.model.LinearProfile;

import java.util.Collection;
import java.util.Map;

public record ExternalProfiles(
    Map<String, LinearProfile> realProfiles,
    Map<String, DiscreteProfile> discreteProfiles,
    Collection<ResourceType> resourceTypes) {}
