package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives;
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents;
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.Timestamp;
import kotlin.NotImplementedError;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An immutable version of a Timeline Plan. Primary use is for Procedural Constraints.
 *
 * TODO: Test if this needs to be sim-related and not plan-related
 */
public final class ReadonlyPlan implements Plan {
  private final gov.nasa.jpl.aerie.types.Plan plan;
  private final Interval bounds;
  private final EvaluationEnvironment environment;

  public ReadonlyPlan(gov.nasa.jpl.aerie.types.Plan plan, final EvaluationEnvironment environment){
    this.plan = plan;
    this.bounds = Interval.between(Duration.ZERO, plan.duration());
    this.environment = environment;
  }

  /** Total extent of the plan's bounds, whether it was simulated on the full extent or not. */
  @NotNull
  @Override
  public Interval totalBounds() {
    return bounds;
  }

  /** The total duration of the plan, whether simulated on the full extent or not. */
  @NotNull
  @Override
  public Duration duration() {
    return plan.duration();
  }

  /** Convert a time instant to a relative duration (relative to plan start). */
  @NotNull
  @Override
  public Duration toRelative(@NotNull final Instant abs) {
    return new Duration(plan.simulationStartTimestamp.microsUntil(new Timestamp(abs)));
  }

  /** Convert a relative duration to a time instant. */
  @NotNull
  @Override
  public Instant toAbsolute(@NotNull final Duration rel) {
    return Duration.addToInstant(plan.planStartInstant(), rel);
  }

  /**
   * Query activity directives.
   *
   * @param type Activity type name to filter by; queries all activities if null.
   * @param deserializer a function from [SerializedValue] to an inner payload type
   */
  @NotNull
  @Override
  public <A> Directives<A> directives(
      @Nullable final String type,
      @NotNull final Function1<? super SerializedValue, ? extends A> deserializer)
  {
    final var directives = plan.activityDirectives()
                               .entrySet()
                               .stream()
                               .filter(entry -> type == null || entry.getValue().serializedActivity().getTypeName().equals(type))
                               .map(entry -> {
                                 final var dir = entry.getValue();
                                 final var dirId = entry.getKey();
                                 final var anchorPoint = dir.anchoredToStart() ? DirectiveStart.Anchor.AnchorPoint.Start
                                                                               : DirectiveStart.Anchor.AnchorPoint.End;
                                 final var dirStart = dir.anchorId() == null ?
                                       new DirectiveStart.Absolute(dir.startOffset())
                                     : new DirectiveStart.Anchor(dir.anchorId(), dir.startOffset(), anchorPoint);

                                 return new Directive<A>(
                                     deserializer.invoke(SerializedValue.of(dir.serializedActivity().getArguments())),
                                     dir.serializedActivity().getTypeName() + " " + dirId.id(),
                                     dirId,
                                     dir.serializedActivity().getTypeName(),
                                     dirStart
                                 );
                               })
                               .toList();
    return new Directives<>(directives);
  }

  @NotNull
  @Override
  public Directives<AnyDirective> directives(@NotNull final String type) {
    return directives(type, AnyDirective.deserializer());
  }

  @NotNull
  @Override
  public Directives<AnyDirective> directives() {
    return directives(null, AnyDirective.deserializer());
  }

  /**
   * Query a resource profile from the external datasets associated with this plan.
   *
   * @param deserializer constructor of the profile, converting [SerializedValue]
   * @param name string name of the resource
   */
  @NotNull
  @Override
  public <V, TL extends SerialSegmentOps<V, TL>> TL resource(
      @NotNull final String name,
      @NotNull final Function1<? super List<Segment<SerializedValue>>, ? extends TL> deserializer)
  {
    final List<Segment<SerializedValue>> segments;
    if(environment.realExternalProfiles().containsKey(name)) {
      segments = environment.realExternalProfiles()
                            .get(name)
                            .profilePieces
                            .stream()
                            .map(s -> new Segment<>(
                                s.interval().toProceduralInterval(),
                                SerializedValue.of(Map.of(
                                    "initial", SerializedValue.of(s.value().initialValue),
                                    "rate", SerializedValue.of(s.value().rate)))))
                            .toList();
    } else if (environment.discreteExternalProfiles().containsKey(name)){
      segments = environment.discreteExternalProfiles()
                            .get(name)
                            .profilePieces
                            .stream()
                            .map(s -> new Segment<>(s.interval().toProceduralInterval(), s.value()))
                            .toList();
    } else {
      throw new IllegalArgumentException("External profile not found: "+name);
    }
    return deserializer.invoke(segments);
  }

  /** Get external events associated with this plan. */
  @NotNull
  @Override
  public ExternalEvents events(@NotNull final EventQuery query) {
    throw new NotImplementedError("Procedural Constraints does not currently support External Events");
  }

  /** Get all external events across all derivation groups associated with this plan. */
  @NotNull
  @Override
  public ExternalEvents events() {
    return events(new EventQuery());
  }
}
