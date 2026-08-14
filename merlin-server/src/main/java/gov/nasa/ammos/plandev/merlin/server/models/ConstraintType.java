package gov.nasa.jpl.aerie.merlin.server.models;

import java.nio.file.Path;

/**
 * Interface defining the types of constraints Aerie accepts.
 */
public sealed interface ConstraintType {
  /**
   * A constraint written in the Aerie TypeScript EDSL.
   * @param definition The raw TypeScript code describing this constraint.
   */
  record EDSL(String definition) implements ConstraintType {}

  /**
   * A constraint written in Java and compiled against Aerie's Constraint Annotation Processor.
   * @param path Path to the JAR containing the compiled constraint code.
   */
  record JAR(Path path) implements ConstraintType {
    public JAR(String path) { this(Path.of(path)); }
  }
}
