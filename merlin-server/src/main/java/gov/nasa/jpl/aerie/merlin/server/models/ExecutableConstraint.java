package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.constraints.model.ConstraintResult;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.model.Violation;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Plan;
import org.jetbrains.annotations.NotNull;

import gov.nasa.ammos.aerie.procedural.constraints.ProcedureMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

public sealed interface ExecutableConstraint extends Comparable<ExecutableConstraint>{
  long order();
  ConstraintRecord record();

  final class EDSLConstraint implements ExecutableConstraint {
    private final ConstraintRecord record;
    private final Expression<ConstraintResult> expression;

    public EDSLConstraint(ConstraintRecord record, Expression<ConstraintResult> expression) {
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

    public HashSet<String> cacheResources(
        Map<String, LinearProfile> realProfiles,
        Map<String, DiscreteProfile> discreteProfiles,
        SimulationResultsHandle simResultsHandle
    ) {
      // get the list of resources that this constraint needs to run
      final var names = new HashSet<String>();
      expression.extractResources(names);

      // get the subset that have yet to be extracted by a prior constraint
      final var newNames = new HashSet<String>();
      for (final var name : names) {
        if (!realProfiles.containsKey(name) && !discreteProfiles.containsKey(name)) {
          newNames.add(name);
        }
      }

      // extract and then cache those resources
      if (!newNames.isEmpty()) {
        final var newProfiles = simResultsHandle.getProfiles(new ArrayList<>(newNames));
        for (final var profile : ProfileSet.unwrapOptional(newProfiles.realProfiles()).entrySet()) {
          realProfiles.put(profile.getKey(), LinearProfile.fromSimulatedProfile(profile.getValue().segments()));
        }
        for (final var _entry : ProfileSet.unwrapOptional(newProfiles.discreteProfiles()).entrySet()) {
          discreteProfiles.put(
              _entry.getKey(),
              DiscreteProfile.fromSimulatedProfile(_entry.getValue().segments()));
        }
      }
      return names;
    }

    public ConstraintResult run(
        SimulationResults preparedResults,
        EvaluationEnvironment environment,
        HashSet<String> resources
    ) {
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

    public ConstraintResult run(
        Plan plan,
        gov.nasa.jpl.aerie.merlin.driver.SimulationResults preparedResults,
        EvaluationEnvironment environment
    ) {
      final ProcedureMapper<?> procedureMapper;
      try {
        final var jar = (ConstraintType.JAR) record.type();
        procedureMapper = ProcedureLoader.loadProcedure(jar.path());
      } catch (ProcedureLoader.ProcedureLoadException e) {
        throw new RuntimeException(e);
      }

      final var timelinePlan = new ReadonlyPlan(plan, environment);
      final var timelineSimResults = new ReadonlyProceduralSimResults(preparedResults, timelinePlan);

      final var violations = Violation.fromProceduralViolations(procedureMapper
          .deserialize(SerializedValue.of(record.arguments()))
          .run(timelinePlan, timelineSimResults), preparedResults);

      return new ConstraintResult(violations, List.of());
    }
  }
}

