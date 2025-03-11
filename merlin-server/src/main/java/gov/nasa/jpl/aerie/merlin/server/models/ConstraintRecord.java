package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;

/**
 * A record of a Constraint to be checked, as interpreted from the database.
 * @param priority The constraint's priority on the plan's specification.
 * @param invocationId The constraint's invocation id on the plan's specification.
 * @param constraintId The constraint's database id.
 * @param revision The revision of the constraint's definition.
 * @param name The user-defined name of the constraint.
 * @param type What language the constraint is in. Contains a type-appropriate definition.
 * @param arguments Arguments to be passed to the constraint. Currently only used with JAR-type constraints.
 */
public record ConstraintRecord(
    long priority,
    long invocationId,
    long constraintId,
    long revision,
    String name,
    String description,
    ConstraintType type,
    Map<String, SerializedValue> arguments
) {}
