package gov.nasa.ammos.plandev.merlin.server.exceptions;

public class InvalidMissionModelTypeException extends Exception {
  public enum ModelType {JAR, JSON}

  public InvalidMissionModelTypeException(ModelType expectedType, ModelType encounteredType) {
    super("Expected a %s-type mission model, encountered a %s-type file.".formatted(expectedType, encounteredType));
  }
}
