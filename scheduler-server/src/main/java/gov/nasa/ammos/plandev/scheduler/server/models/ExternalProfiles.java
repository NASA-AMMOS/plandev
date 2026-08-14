package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;

import java.util.Collection;
import java.util.Map;

public record ExternalProfiles(
    Map<String, LinearProfile> realProfiles,
    Map<String, DiscreteProfile> discreteProfiles,
    Collection<ResourceType> resourceTypes) {}
