package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulePlanGrounder;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SchedulingGrounderTest {
  private final static PlanningHorizon h = new PlanningHorizon(TimeUtility.fromDOY("2025-001T01:01:01.001"), TimeUtility.fromDOY("2025-005T01:01:01.001"));
  private final static Duration t0 = h.getStartAerie();
  private final static Duration d1min = Duration.of(1, Duration.MINUTE);
  private final static Duration d1hr = Duration.of(1, Duration.HOUR);
  private final static Duration t1hr = t0.plus(d1hr);
  private final static Duration t2hr = t0.plus(d1hr.times(2));

  @Test
  public void testChainAnchors(){
    final var at = new ActivityType("at");
    final var id1 = new ActivityDirectiveId(1);
    final var id2 = new ActivityDirectiveId(2);
    final var id3 = new ActivityDirectiveId(3);
    final var act1 = SchedulingActivity.of(id1, at, t0, d1min, null, true);
    final var act2 = SchedulingActivity.of(id2, at, t1hr, d1min, act1.id(), false);
    final var act3 = SchedulingActivity.of(id3, at, t2hr, d1min, act2.id(), false);
    final var acts = List.of(act1, act3, act2);
    final var result = SchedulePlanGrounder.groundSchedule(acts, h.getEndAerie());
    //act 1 should start at 0 min into the plan
    //act 2 should start 61 min into the plan
    //act 3 should be [62 min, 63 min]
    final var act1expt = new ActivityInstance(id1.id(), at.getName(), Map.of(), Interval.between(t0, t0.plus(d1min)), Optional.of(id1));
    final var act2expt = new ActivityInstance(id2.id(), at.getName(), Map.of(), Interval.between(t1hr.plus(t0).plus(d1min), t1hr.plus(t0).plus(d1min).plus(d1min)), Optional.of(id2));
    final var act3expt = new ActivityInstance(id3.id(), at.getName(), Map.of(), Interval.between(t0.plus(Duration.of(182, Duration.MINUTES)), t0.plus(Duration.of(183, Duration.MINUTES))), Optional.of(id3));
    assertTrue(result.get().contains(act1expt));
    assertTrue(result.get().contains(act2expt));
    assertTrue(result.get().contains(act3expt));
  }

  @Test
  public void testEmptyDueToEmptyDuration(){
    final var at = new ActivityType("at");
    final var id1 = new ActivityDirectiveId(1);
    final var act1 = SchedulingActivity.of(id1, at, t0, null, null, true);
    final var result = SchedulePlanGrounder.groundSchedule(List.of(act1), h.getEndAerie());
    assertTrue(result.isEmpty());
  }

  @Test
  public void testAnchoredToPlanEnd(){
    final var at = new ActivityType("at");
    final var id1 = new ActivityDirectiveId(1);
    final var act1 = SchedulingActivity.of(id1, at, Duration.negate(d1hr), d1min, null, false);
    final var result = SchedulePlanGrounder.groundSchedule(List.of(act1), h.getEndAerie());
    final var act1expt = new ActivityInstance(id1.id(), at.getName(), Map.of(), Interval.between(h.getEndAerie().minus(d1hr), h.getEndAerie().minus(d1hr).plus(d1min)), Optional.of(id1));
    assertEquals(act1expt, result.get().get(0));
  }


  @Test
  public void noAnchor(){
    final var at = new ActivityType("at");
    final var id1 = new ActivityDirectiveId(1);
    final var id2 = new ActivityDirectiveId(2);
    final var act1 = SchedulingActivity.of(id1, at, t0, d1min, null, true);
    final var act2 = SchedulingActivity.of(id2, at, t1hr, d1min, null, true);
    final var result = SchedulePlanGrounder.groundSchedule(List.of(act1, act2), h.getEndAerie());
    final var act1expt = new ActivityInstance(id1.id(), at.getName(), Map.of(), Interval.between(t0, t0.plus(d1min)), Optional.of(id1));
    final var act2expt = new ActivityInstance(id2.id(), at.getName(), Map.of(), Interval.between(t1hr, t1hr.plus(d1min)), Optional.of(id2));
    assertTrue(result.get().contains(act1expt));
    assertTrue(result.get().contains(act2expt));
  }

  @Test
  public void startTimeAnchor(){
    final var at = new ActivityType("at");
    final var id1 = new ActivityDirectiveId(1);
    final var id2 = new ActivityDirectiveId(2);
    final var act1 = SchedulingActivity.of(id1, at, t1hr, d1min, null, true);
    final var act2 = SchedulingActivity.of(id2, at, t1hr, d1min, act1.id(), true);
    final var result = SchedulePlanGrounder.groundSchedule(List.of(act1, act2), h.getEndAerie());
    final var act1expt = new ActivityInstance(id1.id(), at.getName(), Map.of(), Interval.between(t1hr, t1hr.plus(d1min)), Optional.of(id1));
    final var act2expt = new ActivityInstance(id2.id(), at.getName(), Map.of(), Interval.between(t2hr, t2hr.plus(d1min)), Optional.of(id2));
    assertTrue(result.get().contains(act1expt));
    assertTrue(result.get().contains(act2expt));
  }
}
