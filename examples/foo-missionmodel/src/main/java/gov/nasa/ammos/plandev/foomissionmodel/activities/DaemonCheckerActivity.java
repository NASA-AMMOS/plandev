package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;

/**
 * An activity that checks that the TimeTrackerDaemon has run the correct number of times.
 * @param minutesElapsed The expected number of minutes elapsed.
 */
@ActivityType("DaemonCheckerActivity")
public record DaemonCheckerActivity(int minutesElapsed) {
  @EffectModel
  public void run(final Mission mission) {
    if (mission.timeTrackerDaemon.getMinutesElapsed() != minutesElapsed) {
      throw new RuntimeException("Minutes elapsed is incorrect. TimeTrackerDaemon may have stopped."
                                 + "\n\tExpected: " +minutesElapsed+" Actual: "+mission.timeTrackerDaemon.getMinutesElapsed() );
    }
  }
}
