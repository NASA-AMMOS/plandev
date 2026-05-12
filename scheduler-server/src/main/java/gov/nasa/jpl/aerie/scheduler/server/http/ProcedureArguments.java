package gov.nasa.jpl.aerie.scheduler.server.http;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.GoalId;

import java.util.Map;

public record ProcedureArguments(GoalId goalId, Map<String, SerializedValue> arguments) {}
