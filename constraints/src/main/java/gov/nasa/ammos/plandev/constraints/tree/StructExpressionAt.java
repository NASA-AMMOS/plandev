package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.IntervalMap;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Used only for instantiating, as a building block, not for any other computation
 * @param fields
 */
public record StructExpressionAt(
    Map<String, ProfileExpression<?>> fields) implements Expression<DiscreteProfile> {

  @Override
  public DiscreteProfile evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    //Evaluating this expression is computing the value of member expressions at the lower time bound.
    //Even if the expression returns a discrete profile, this profile has a constant value, a map associating a string to a serialized value,
    //at the singleton timepoint it has been computed at.
    //The instantiation and use of valueAt is necessary here because the profile are not only discrete but also "continuous" in the form of linear profiles.
    if(!bounds.isSingleton()){
      throw new Error("The StructExpressionAt node should be used only with singleton bounds.");
    }
    final var retMap = evaluateMap(results, bounds.start, environment);
    return new DiscreteProfile(IntervalMap.<SerializedValue>builder()
                                          .set(Segment.of(bounds, SerializedValue.of(retMap)))
                                          .build());
  }

  public Map<String, SerializedValue> evaluateMap(final SimulationResults results, final Duration time, final EvaluationEnvironment environment) {
    final var retMap = new HashMap<String, SerializedValue>();
    for (final var field: fields.entrySet()) {
      retMap.put(field.getKey(), field.getValue().evaluate(results, Interval.at(time), environment).valueAt(time).orElseThrow(
          () -> new Error("Sub profile for key " + field.getKey() + " has no value at time " + time)
      ));
    }
    return retMap;
  }

  @Override
  public void extractResources(final Set<String> names) {
    for (final var field: fields.entrySet()) {
      field.getValue().extractResources(names);
    }
  }

  @Override
  public String prettyPrint(final String prefix) {
    StringBuilder fieldString = new StringBuilder();
    for (final var field: fields.entrySet()) {
      fieldString
          .append(field.getKey())
          .append(": ")
          .append(field.getValue().prettyPrint(prefix))
          .append(", ");
    }
    return String.format(
        "\n%s(struct {%s})",
        prefix,
        fieldString
    );
  }
}
