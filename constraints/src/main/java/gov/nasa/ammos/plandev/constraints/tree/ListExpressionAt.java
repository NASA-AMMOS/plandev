package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalMap;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record ListExpressionAt(
    List<ProfileExpression<?>> elements
) implements Expression<DiscreteProfile> {

  @Override
  public DiscreteProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    //Evaluating this expression is computing the value of member expressions at the lower time bound.
    //Even if the expression returns a discrete profile, this profile has a constant value, a list of serialized values,
    //at the singleton timepoint it has been computed at.
    //The instantiation and use of valueAt is necessary here because the profile are not only discrete but also "continuous" in the form of linear profiles.
    if(!bounds.isSingleton()){
      throw new Error("The ListExpressionAt node should be used only with singleton bounds.");
    }
    final var retList = new ArrayList<SerializedValue>();
    for (final var field: elements) {
      retList.add(field.evaluate(results, bounds, environment)
                       .valueAt(bounds.start)
                       .orElseThrow(() -> new Error("Element profile in list has no value at time " + bounds.start)));
    }
    return new DiscreteProfile(IntervalMap.<SerializedValue>builder()
                                          .set(Segment.of(bounds, SerializedValue.of(retList)))
                                          .build());
  }

  @Override
  public void extractResources(final Set<String> names) {
    for (final var field: elements) {
      field.extractResources(names);
    }
  }

  @Override
  public String prettyPrint(final String prefix) {
    StringBuilder fieldString = new StringBuilder();
    for (final var field: elements) {
      fieldString
          .append(field)
          .append(", ");
    }
    return String.format(
        "\n%s(array [%s])",
        prefix,
        fieldString
    );
  }
}
