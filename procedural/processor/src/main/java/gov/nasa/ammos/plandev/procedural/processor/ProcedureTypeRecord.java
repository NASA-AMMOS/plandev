package gov.nasa.ammos.plandev.procedural.processor;

public record ProcedureTypeRecord(
    String fullyQualifiedClass,
    String name,
    InputTypeRecord inputType) {}
