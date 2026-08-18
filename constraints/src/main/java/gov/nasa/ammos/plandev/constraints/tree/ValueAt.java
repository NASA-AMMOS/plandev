package gov.nasa.ammos.plandev.constraints.tree;

import gov.nasa.ammos.plandev.constraints.model.DiscreteProfile;
import gov.nasa.ammos.plandev.constraints.model.EvaluationEnvironment;
import gov.nasa.ammos.plandev.constraints.model.Profile;
import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Interval;
import gov.nasa.ammos.plandev.constraints.time.IntervalMap;
import gov.nasa.ammos.plandev.constraints.time.Segment;
import gov.nasa.ammos.plandev.constraints.time.Spans;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;

import java.util.Set;

public record ValueAt<P extends Profile<P>>(
    ProfileExpression<P> profile,
    Expression<Spans> timepoint
) implements Expression<DiscreteProfile> {


  @Override
  public DiscreteProfile evaluate(
      final SimulationResults results,
      final Interval bounds,
      final EvaluationEnvironment environment)
  {
    final var time = timepoint.evaluate(results, Interval.FOREVER, environment);
    final var timepoint = time.iterator().next().interval().start;
    final var res = this.profile.evaluate(results, Interval.at(timepoint), environment);
    //REVIEW: SHOULD ASSERT A BUNCH OF THINGS HERE SO IT IS NOT WRONGLY USED
    final var value = res.valueAt(timepoint);
    if(value.isEmpty()){
      throw new Error("Profile has no value at time " + timepoint);
    }
    return new DiscreteProfile(IntervalMap.<SerializedValue>builder()
                                          .set(Segment.of(bounds, value.get()))
                                          .build());
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(valueAt %s %s)",
        prefix,
        this.profile.prettyPrint(),
        timepoint.prettyPrint()
    );
  }

  @Override
  public void extractResources(final Set<String> names) {
    this.profile.extractResources(names);
    this.timepoint.extractResources(names);
  }
}
