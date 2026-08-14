package gov.nasa.jpl.aerie.scheduler.model;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.driver.StartOffsetReducer;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class SchedulePlanGrounder {
  public static Optional<List<ActivityInstance>> groundSchedule(
      final List<SchedulingActivity> schedulingActivityList,
      final Duration planDuration
  ){
    final var groundedDirectives = new HashMap<ActivityDirectiveId, ActivityInstance>();

    final var idMap = schedulingActivityList
        .stream()
        .map(a -> Pair.of(a.id(), a))
        .filter($ -> $.getKey() != null)
        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

    final var maxDirectiveId = idMap.keySet()
        .stream().map(ActivityDirectiveId::id)
        .max(Long::compare);

    final var converted = schedulingActivityList
        .stream()
        .map(a -> Pair.of(
            a.id(),
            new ActivityDirective(
                a.startOffset(),
                a.type().getName(),
                a.arguments(),
                (a.anchorId() == null) ? null : new ActivityDirectiveId(a.anchorId().id()),
                a.anchoredToStart()
            )))
        .filter($ -> $.getKey() != null)
        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
    final var converter = new StartOffsetReducer(planDuration, converted);
    var computed = converter.compute();
    computed = StartOffsetReducer.filterOutNegativeStartOffset(computed);

    for(final var directive: computed.entrySet()){
      Duration offset = Duration.ZERO;
      final var idActivity = directive.getKey();
      if(idActivity != null){
        if(groundedDirectives.get(idActivity) == null){
          return Optional.empty();
        } else {
          final var alreadyGroundedAct = groundedDirectives.get(idActivity);
          offset = alreadyGroundedAct.interval().end;
        }
      }
      for(final Pair<ActivityDirectiveId, Duration> dependentDirective : directive.getValue()) {
        final var dependentId = dependentDirective.getKey();
        final var dependentOriginalActivity = idMap.get(dependentId);
        final var startTime = offset.plus(dependentDirective.getValue());
        //happens only in tests
        if(dependentOriginalActivity.duration() == null){
          return Optional.empty();
        }
        groundedDirectives.put(dependentId, new ActivityInstance(
            dependentId.id(),
            dependentOriginalActivity.type().getName(),
            dependentOriginalActivity.arguments(),
            Interval.between(startTime, startTime.plus(dependentOriginalActivity.duration())),
            Optional.of(new ActivityDirectiveId(dependentId.id()))
        ));
      }
    }
    final var result = new java.util.ArrayList<>(groundedDirectives.values().stream().toList());

    final var instanceIdCounter = new AtomicLong(maxDirectiveId.orElse(0L) + 1L);
    result.addAll(
        schedulingActivityList
            .stream()
            .filter($ -> $.id() == null)
            .map($ -> new ActivityInstance(
                instanceIdCounter.getAndIncrement(),
                $.type().getName(),
                $.arguments(),
                Interval.between($.startOffset(), $.startOffset().plus($.duration())),
                Optional.empty()
            ))
            .toList()
    );

    return Optional.of(result);
  }
}
