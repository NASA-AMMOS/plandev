package gov.nasa.ammos.plandev.scheduler.server.models;

import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;

public record ResourceType(String name, ValueSchema schema) {}
