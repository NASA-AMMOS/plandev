package gov.nasa.ammos.plandev.scheduler.server.http;

import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.scheduler.model.GoalId;

import java.util.Map;

public record ProcedureArguments(GoalId goalId, Map<String, SerializedValue> arguments) {}
