package gov.nasa.jpl.plandev.scheduler.server.models;

import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema;

public record ResourceType(String name, ValueSchema schema) {}
