package gov.nasa.jpl.plandev.merlin.server.models;

import java.nio.file.Path;

/**
 * Interface defining the types of constraints PlanDev accepts.
 */
public sealed interface ConstraintType {
  /**
   * A constraint written in the PlanDev TypeScript EDSL.
   * @param definition The raw TypeScript code describing this constraint.
   */
  record EDSL(String definition) implements ConstraintType {}

  /**
   * A constraint written in Java and compiled against PlanDev's Constraint Annotation Processor.
   * @param path Path to the JAR containing the compiled constraint code.
   */
  record JAR(Path path) implements ConstraintType {
    public JAR(String path) { this(Path.of(path)); }
  }
}
