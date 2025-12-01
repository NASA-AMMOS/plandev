package gov.nasa.jpl.plandev.scheduler.server.models;

import gov.nasa.jpl.plandev.constraints.model.DiscreteProfile;
import gov.nasa.jpl.plandev.constraints.model.LinearProfile;

import java.util.Collection;
import java.util.Map;

public record ExternalProfiles(
    Map<String, LinearProfile> realProfiles,
    Map<String, DiscreteProfile> discreteProfiles,
    Collection<ResourceType> resourceTypes) {}
