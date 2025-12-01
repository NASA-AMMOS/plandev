package gov.nasa.jpl.plandev.merlin.processor.metamodel;

public record ParameterValidationRecord(String methodName, String[] subjects, String failureMessage, boolean isSimpleValidation) { }
