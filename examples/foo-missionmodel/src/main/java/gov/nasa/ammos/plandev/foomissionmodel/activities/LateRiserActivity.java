package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;

/**
 * An activity that can't be scheduled in the first 60 seconds of the horizon
 */
@ActivityType("LateRiser")
public record LateRiserActivity() {
  @EffectModel
  public void run(final Mission mission) {
    if (mission.timeTrackerDaemon.getMinutesElapsed() < 1) {
      throw new RuntimeException("Can't be scheduled THAT early");
    }
  }
}
