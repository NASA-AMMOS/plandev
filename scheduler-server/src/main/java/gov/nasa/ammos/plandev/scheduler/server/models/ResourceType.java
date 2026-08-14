package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

public record ResourceType(String name, ValueSchema schema) {}
