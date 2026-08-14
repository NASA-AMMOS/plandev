package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import org.apache.commons.lang3.function.TriFunction;

import java.util.Set;
import java.util.function.Supplier;

public interface Expression<T> {
  T evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment);
  String prettyPrint(final String prefix);
  /** Add the resources referenced by this expression to the given set. **/
  void extractResources(Set<String> names);

  default T evaluate(final SimulationResults results, final EvaluationEnvironment environment){
    return this.evaluate(results, results.bounds, environment);
  }

  default T evaluate(final SimulationResults results, final Interval bounds){
    return this.evaluate(results, bounds, new EvaluationEnvironment());
  }

  default T evaluate(final SimulationResults results) {
    return this.evaluate(results, new EvaluationEnvironment());
  }
  default String prettyPrint() {
    return this.prettyPrint("");
  }

  static <T> Expression<T> of(TriFunction<SimulationResults, Interval, EvaluationEnvironment, T> eval) {
    return new Expression<T>() {
      @Override
      public T evaluate(
          final SimulationResults results,
          final Interval bounds,
          final EvaluationEnvironment environment)
      {
        return eval.apply(results, bounds, environment);
      }

      @Override
      public String prettyPrint(final String prefix) {
        return "(anonymous expression)";
      }

      @Override
      public void extractResources(final Set<String> names) {

      }
    };
  }

  static <T> Expression<T> of(Supplier<T> eval) {
    return Expression.of((s, i, e) -> eval.get());
  }
}
