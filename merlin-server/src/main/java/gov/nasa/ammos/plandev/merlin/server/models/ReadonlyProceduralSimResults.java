package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Instances;
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyInstance;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReadonlyProceduralSimResults implements SimulationResults {
  private final gov.nasa.jpl.aerie.merlin.driver.SimulationResults merlinResults;
  private final Plan plan;

  public ReadonlyProceduralSimResults(
      gov.nasa.jpl.aerie.merlin.driver.SimulationResults merlinResults,
      Plan plan
  ) {
    this.merlinResults = merlinResults;
    this.plan = plan;
  }

  /** Queries all activity instances, deserializing them as [AnyInstance]. **/
  @NotNull
  @Override
  public Instances<AnyInstance> instances() {
    return instances(null, AnyInstance.Companion.deserializer());
  }

  /** Queries activity instances, filtered by type, deserializing them as [AnyInstance]. **/
  @NotNull
  @Override
  public Instances<AnyInstance> instances(@NotNull final String type) {
    return instances(type, AnyInstance.Companion.deserializer());
  }

  /**
   * Query activity instances.
   *
   * @param type Activity type name to filter by; queries all activities if null.
   * @param deserializer a function from [SerializedValue] to an inner payload type
   */
  @NotNull
  @Override
  public <A> Instances<A> instances(
      @Nullable final String type,
      @NotNull final Function1<? super SerializedValue, ? extends A> deserializer)
  {
    final var instances = new ArrayList<Instance<A>>();

    // Add the simulated activities of the correct type
    instances.addAll(merlinResults.simulatedActivities
        .entrySet()
        .stream()
        // Filter on type if it's defined, else return all simulated activities
        .filter(entry -> type == null || entry.getValue().type().equals(type))
        .map(entry -> {
          final var act = entry.getValue();
          final Map<String, SerializedValue> serializedActivity = Map.of(
              "arguments", SerializedValue.of(act.arguments()),
              "computedAttributes", act.computedAttributes());
          final var startTime = plan.toRelative(act.start());
          final var interval = new Interval(startTime, startTime.plus(act.duration()));
          return new Instance<A>(
              deserializer.invoke(SerializedValue.of(serializedActivity)),
              act.type(),
              entry.getKey(),
              act.directiveId().orElse(null),
              act.parentId(),
              interval
          );
        })
        .toList());

    // Add the unfinished activities of the correct type
    instances.addAll(merlinResults.unfinishedActivities
        .entrySet()
        .stream()
        // Filter on type if it's defined, else return all unfinished activities
        .filter(entry -> type == null || entry.getValue().type().equals(type))
        .map(entry -> {
          final var act = entry.getValue();
          final Map<String, SerializedValue> serializedActivity = Map.of(
              "arguments", SerializedValue.of(act.arguments()),
              "computedAttributes", SerializedValue.of(Map.of()));
          final var startTime = plan.toRelative(act.start());
          final var interval = new Interval(startTime, simBounds().end);
          return new Instance<A>(
              deserializer.invoke(SerializedValue.of(serializedActivity)),
              act.type(),
              entry.getKey(),
              act.directiveId().orElse(null),
              act.parentId(),
              interval
          );
        })
        .toList());

    return new Instances<>(instances);
  }

  /**
   * Query a resource profile from this simulation dataset.
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
    final List<Segment<SerializedValue>> segments = new ArrayList<>();
    if (merlinResults.realProfiles.containsKey(name)) {
      final var s = merlinResults.realProfiles
          .get(name)
          .segments();
      // Add initial segment
      segments.add(new Segment<>(
          new Interval(simBounds().start, s.getFirst().extent()),
          SerializedValue.of(Map.of(
              "initial", SerializedValue.of(s.getFirst().dynamics().initial),
              "rate", SerializedValue.of(s.getFirst().dynamics().rate)))));
      // Add remaining segments
      var priorStart = simBounds().start.plus(s.getFirst().extent());
      for (int i = 1; i < s.size(); ++i) {
        segments.add(new Segment<>(
            new Interval(priorStart, priorStart.plus(s.get(i).extent())),
            SerializedValue.of(Map.of(
                "initial", SerializedValue.of(s.get(i).dynamics().initial),
                "rate", SerializedValue.of(s.get(i).dynamics().rate)))
        ));
        priorStart = priorStart.plus(s.get(i).extent());
      }
    } else if (merlinResults.discreteProfiles.containsKey(name)) {
      final var s = merlinResults.discreteProfiles
          .get(name)
          .segments();
      // Add initial segment
      segments.add(new Segment<>(
          new Interval(simBounds().start, s.getFirst().extent()), s.getFirst().dynamics()));
      // Add remaining segments
      var priorStart = simBounds().start.plus(s.getFirst().extent());
      for (int i = 1; i < s.size(); ++i) {
        segments.add(new Segment<>(
            new Interval(priorStart, priorStart.plus(s.get(i).extent())), s.get(i).dynamics()
        ));
        priorStart = priorStart.plus(s.get(i).extent());
      }
    } else {
      throw new IllegalArgumentException("No such resource: " + name);
    }
    return deserializer.invoke(segments);
  }

  /** Bounds on which the plan was most recently simulated. */
  @NotNull
  @Override
  public Interval simBounds() {
    return Interval.between(plan.toRelative(merlinResults.startTime), merlinResults.duration);
  }

  /** Whether these results are up-to-date with all changes. */
  @Override
  public boolean isStale() {
    return false;
  }

  @NotNull
  @Override
  public <A> Directives<A> inputDirectives(@NotNull final Function1<? super SerializedValue, ? extends A> deserializer) {
    return plan.directives(null, deserializer);
  }

  @NotNull
  @Override
  public Directives<AnyDirective> inputDirectives() {
    return plan.directives();
  }
}
