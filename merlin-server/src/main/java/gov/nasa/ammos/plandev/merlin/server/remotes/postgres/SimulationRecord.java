package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Timestamp;

import java.util.Map;
import java.util.Optional;

public record SimulationRecord(
    long id,
    long revision,
    long planId,
    Optional<Long> simulationTemplateId,
    Map<String, SerializedValue> arguments,
    Timestamp simulationStartTime,
    Timestamp simulationEndTime
    ) {}
