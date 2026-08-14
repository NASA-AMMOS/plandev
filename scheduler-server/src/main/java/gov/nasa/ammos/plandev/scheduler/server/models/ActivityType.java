package gov.nasa.jpl.aerie.scheduler.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Map;

public record ActivityType(String name, Map<String, ValueSchema> parameters, Map<String, Map<String, SerializedValue>> presets) {}
