package gov.nasa.ammos.plandev.merlin.processor.metamodel;

public record ParameterValidationRecord(String methodName, String[] subjects, String failureMessage, boolean isSimpleValidation) { }
