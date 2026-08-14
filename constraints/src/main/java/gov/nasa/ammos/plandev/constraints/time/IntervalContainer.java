package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.constraints.model.LinearProfile;
import gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

public interface IntervalContainer<T extends IntervalContainer<T>> {
  Spans split(final Interval bounds, final int numberOfSubIntervals, final Inclusivity internalStartInclusivity, final Inclusivity internalEndInclusivity);
  LinearProfile accumulatedDuration(final Duration unit);
  T starts();
  T ends();
  T shiftEdges(final Duration fromStart, final Duration fromEnd);
  T select(final Interval... intervals);
}
