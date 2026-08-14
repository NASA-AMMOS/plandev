package gov.nasa.jpl.aerie.constraints.tree;

import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.constraints.time.Spans;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ActivitySpan implements Expression<Spans> {
  public final String activityAlias;

  public ActivitySpan(final String activityAlias) {
    this.activityAlias = activityAlias;
  }

  @Override
  public Spans evaluate(final SimulationResults results, final Interval bounds, final EvaluationEnvironment environment) {
    final var activity = environment.activityInstances().get(this.activityAlias);
    return new Spans(Segment.of(activity.interval(), Optional.of(new Spans.Metadata(activity))));
  }

  @Override
  public void extractResources(final Set<String> names) { }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(during %s)",
        prefix,
        this.activityAlias
    );
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ActivitySpan)) return false;
    final var o = (ActivitySpan)obj;

    return Objects.equals(this.activityAlias, o.activityAlias);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.activityAlias);
  }
}
