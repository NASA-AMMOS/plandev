package gov.nasa.jpl.aerie.merlin.processor.metamodel;

public record ParameterValidationRecord(String methodName, String[] subjects, String failureMessage, boolean isSimpleValidation) { }
