package gov.nasa.ammos.aerie.procedural.processor;

public record ProcedureTypeRecord(
    String fullyQualifiedClass,
    String name,
    InputTypeRecord inputType) {}
