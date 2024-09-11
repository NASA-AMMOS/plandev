package gov.nasa.jpl.aerie.orchestration.scheduling;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.ammos.aerie.procedural.timeline.util.duration.DurationKt;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An adapter from the type-utils {@link Plan} class to the procedural {@link gov.nasa.ammos.aerie.procedural.timeline.plan.Plan} interface.
 */
public record TypeUtilsProceduralPlan(Plan plan) implements gov.nasa.ammos.aerie.procedural.timeline.plan.Plan {
  @NotNull
  @Override
  public Interval totalBounds() {
    return Interval.between(Duration.ZERO, plan.duration());
  }

  @NotNull
  @Override
  public Duration toRelative(@NotNull Instant abs) {
    return DurationKt.minus(abs, plan.planStartInstant());
  }

  @NotNull
  @Override
  public Instant toAbsolute(@NotNull Duration rel) {
    return DurationKt.plus(plan.planStartInstant(), rel);
  }

  @NotNull
  @Override
  public <A> Directives<A> directives(
      String type,
      @NotNull Function1<? super SerializedValue, ? extends A> deserializer)
  {
    final Stream<Map.Entry<ActivityDirectiveId, ActivityDirective>> activities;
    if (type == null) {
      activities = plan.activityDirectives().entrySet().stream();
    } else {
      activities = plan.activityDirectives().entrySet().stream()
                       .filter($ -> $.getValue().serializedActivity().getTypeName().equals(type));
    }

    final List<Directive<A>> result = activities.map($ -> {
      final var id = $.getKey();
      final var act = $.getValue();
      return new Directive<>(
          (A) deserializer.invoke(SerializedValue.of(act.serializedActivity().getArguments())),
          "Name unavailable",
          id,
          act.serializedActivity().getTypeName(),
          act.anchorId() == null
              ? new DirectiveStart.Absolute(act.startOffset())
              : new DirectiveStart.Anchor(
                  act.anchorId(),
                  act.startOffset(),
                  act.anchoredToStart()
                      ? DirectiveStart.Anchor.AnchorPoint.Start
                      : DirectiveStart.Anchor.AnchorPoint.End
              )
      );
    }).toList();

    return new Directives<>(result);
  }
}
