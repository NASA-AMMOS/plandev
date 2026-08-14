package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.constraints.tree.ActivitySpan;
import gov.nasa.jpl.aerie.constraints.tree.ForEachActivitySpans;
import gov.nasa.jpl.aerie.constraints.tree.Not;
import gov.nasa.jpl.aerie.constraints.tree.Or;
import gov.nasa.jpl.aerie.constraints.tree.WindowsFromSpans;
import gov.nasa.jpl.aerie.constraints.tree.WindowsWrapperExpression;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingCondition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestUtility {

  public static boolean emptyPlan(Plan plan) {
    return plan.getActivities().isEmpty();
  }
  public static boolean activityStartingAtTime(Plan plan, Duration time, ActivityType activityType) {
    List<SchedulingActivity> acts = plan.getActivitiesByTime();
    for (SchedulingActivity act : acts) {
      if (act.getType().equals(activityType) && act.startOffset().compareTo(time) == 0) {
        return true;
      }
    }
    return false;
  }

  public static boolean containsActivity(Plan plan, Duration startTime, Duration endTime, ActivityType activityType) {
    List<SchedulingActivity> acts = plan.getActivitiesByTime();
    for (SchedulingActivity act : acts) {
      if (act.getType().equals(activityType) &&
          act.startOffset().compareTo(startTime) == 0 &&
          act.getEndTime().compareTo(endTime) == 0) {
        return true;
      }
    }
    return false;
  }
  /**
   * Returns true if there is at least one activity of type activityType in timewindows tw in the plan
   */
  public static boolean atLeastOneActivityOfTypeInTW(Plan plan, Windows tw, ActivityType activityType) {
    for (Interval interval : tw.iterateEqualTo(true)) {
      if (!atLeastOneActivityOfTypeInRange(plan, interval, activityType)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if there is at least one activity of type activityType in the time range tw in the plan
   */
  public static boolean atLeastOneActivityOfTypeInRange(Plan plan, Interval interval, ActivityType activityType) {

    List<SchedulingActivity> acts = plan.getActivitiesByTime();
    for (SchedulingActivity act : acts) {
      if (act.getType().equals(activityType)
          && act.startOffset().compareTo(interval.start) >= 0
          && act.getEndTime().compareTo(interval.end) <= 0) {
        return true;
      }
    }
    return false;
  }

  public static SchedulingCondition createExclusionSchedulingZone(final ActivityType activityType, final Windows exclusionZone){
    return new SchedulingCondition(
        new Not(
            new WindowsWrapperExpression(exclusionZone)
        ),
        List.of(activityType));
  }

  public static List<SchedulingCondition> createAutoMutexGlobalSchedulingCondition(final ActivityType activityType) {
    return List.of(
        new SchedulingCondition(
            new Not(
                new Or(
                    new WindowsFromSpans(
                        new ForEachActivitySpans(
                            activityType.getName(),
                            "span activity alias 0",
                            new ActivitySpan("span activity alias 0"))
                    )
                )
            ),
            List.of(activityType)),
        new SchedulingCondition(
            new Not(
                new Or(
                    new WindowsFromSpans(
                        new ForEachActivitySpans(
                            activityType.getName(),
                            "span activity alias 1",
                            new ActivitySpan("span activity alias 1"))
                    )
                )
            ),
            List.of(activityType)
        )
    );
  }

  /**
   * Creates a list of SchedulingCondition that are equivalent to a mutex constraint between 2 activity types
   */
  public static List<SchedulingCondition> createMutex(final ActivityType activityType1, final ActivityType activityType2) {
    return List.of(
        new SchedulingCondition(
            new Not(
                new Or(
                    new WindowsFromSpans(
                        new ForEachActivitySpans(
                            activityType1.getName(),
                            "span activity alias 0",
                            new ActivitySpan("span activity alias 0"))
                    )
                )
            ),
            List.of(activityType2)),
        new SchedulingCondition(
            new Not(
                new Or(
                    new WindowsFromSpans(
                        new ForEachActivitySpans(
                            activityType2.getName(),
                            "span activity alias 1",
                            new ActivitySpan("span activity alias 1"))
                    )
                )
            ),
            List.of(activityType1)
        )
    );
  }

  /**
   * Returns true if plan does not contain specific activity instance act
   */
  public static boolean doesNotContainActivity(Plan plan, SchedulingActivity act) {
    for (SchedulingActivity actI : plan.getActivitiesByTime()) {
      if (actI.equals(act)) {
        return false;
      }
    }
    return true;
  }

    /**
   * Returns true if plan contains specific activity instance act
   */
  public static boolean containsExactlyActivity(Plan plan, SchedulingActivity act) {
    for (SchedulingActivity actI : plan.getActivitiesByTime()) {
      if (actI.equalsInProperties(act)) {
        return true;
      }
    }
    return false;
  }

  public static Instant timeFromEpochMillis(long millis){
    return Instant.ofEpochMilli(millis);
  }
  public static Instant timeFromEpochSeconds(int seconds){
    return timeFromEpochMillis(seconds * 1000L);
  }
  public static Instant timeFromEpochHours(int hours) {
    return timeFromEpochSeconds(hours * 60 * 60);
  }
  public static Instant timeFromEpochDays(int days) {
    return timeFromEpochHours(days * 24);
  }

  /** matches activities if they agree in everything except the (possibly auto-generated) names **/
  public static void assertSetEquality(List<SchedulingActivity> expected, List<SchedulingActivity> actual) {
    assertEquals(expected.size(), actual.size());
    final var backupExpected = new ArrayList<>(expected);
    for (SchedulingActivity directive : actual) {
      final int matchIndex = getIndexSADList(backupExpected, directive);
      assertNotEquals(-1, matchIndex);
      backupExpected.remove(matchIndex); // Remove to avoid similar elements matching twice
    }
  }

  private static int getIndexSADList(List<SchedulingActivity> list, SchedulingActivity directive) {
    for (int i = 0; i < list.size(); ++i) {
      if (list.get(i).equalsInProperties(directive)) {
        return i;
      }
    }
    return -1;
  }
}
