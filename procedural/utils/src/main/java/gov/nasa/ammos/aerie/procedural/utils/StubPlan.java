package gov.nasa.ammos.aerie.procedural.utils;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives;
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents;
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import kotlin.NotImplementedError;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public class StubPlan implements Plan {
  @NotNull
  @Override
  public Interval totalBounds() {
    throw new NotImplementedError();
  }

  @NotNull
  @Override
  public Duration toRelative(@NotNull final Instant abs) {
    throw new NotImplementedError();
  }

  @NotNull
  @Override
  public Instant toAbsolute(@NotNull final Duration rel) {
    throw new NotImplementedError();
  }

  @NotNull
  @Override
  public <A> Directives<A> directives(
      @Nullable final String type,
      @NotNull final Function1<? super SerializedValue, ? extends A> deserializer)
  {
    throw new NotImplementedError();
  }

  @NotNull
  @Override
  public <V, TL extends SerialSegmentOps<V, TL>> TL resource(
      @NotNull final String name,
      @NotNull final Function1<? super List<Segment<SerializedValue>>, ? extends TL> deserializer)
  {
    throw new NotImplementedError();
  }

  @NotNull
  @Override
  public ExternalEvents events(@NotNull final EventQuery query) {
    throw new NotImplementedError();
  }
}
