package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.constraints.model.EDSLConstraintResult;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.model.Violation;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;

import java.util.List;
import java.util.HashSet;

public sealed interface ExecutableConstraint extends Comparable<ExecutableConstraint>{
  long order();
  ConstraintRecord record();

  final class EDSLConstraint implements ExecutableConstraint {
    private final ConstraintRecord record;
    private final Expression<EDSLConstraintResult> expression;

    public EDSLConstraint(ConstraintRecord record, Expression<EDSLConstraintResult> expression) {
      this.record = record;
      this.expression = expression;
    }

    @Override
    public ConstraintRecord record() {
      return record;
    }

    @Override
    public long order() {
      return record.priority();
    }

    @Override
    public int compareTo(@NotNull final ExecutableConstraint o) {
      return Long.compare(order(), o.order());
    }

    public EDSLConstraintResult run(
        SimulationResults preparedResults,
        EvaluationEnvironment environment
    ) {
      // get the list of resources that this constraint needs to run
      final var resources = new HashSet<String>();
      expression.extractResources(resources);

      // evaluate the constraint
      final var result = expression.evaluate(preparedResults, environment);
      result.constraintName = record.name();
      result.constraintRevision = record.revision();
      result.constraintId = record.constraintId();
      result.resourceIds = List.copyOf(resources);

      return result;
    }
  }

  record JARConstraint(ConstraintRecord record) implements ExecutableConstraint {
    @Override
    public long order() {
      return record.priority();
    }

    @Override
    public int compareTo(@NotNull final ExecutableConstraint o) {
      return Long.compare(order(), o.order());
    }

    public ProceduralConstraintResult run(
        ReadonlyPlan plan,
        ReadonlyProceduralSimResults simResults,
        gov.nasa.jpl.aerie.merlin.driver.SimulationResults merlinResults
    ) {
      final ProcedureMapper<?> procedureMapper;
      try {
        final var jar = (ConstraintType.JAR) record.type();
        procedureMapper = ProcedureLoader.loadProcedure(jar.path());
      } catch (ProcedureLoader.ProcedureLoadException e) {
        throw new RuntimeException(e);
      }

      final var violations = Violation.fromProceduralViolations(procedureMapper
          .deserialize(SerializedValue.of(record.arguments()))
          .run(plan, simResults), merlinResults);

      return new ProceduralConstraintResult(violations, record);
    }
  }
}

