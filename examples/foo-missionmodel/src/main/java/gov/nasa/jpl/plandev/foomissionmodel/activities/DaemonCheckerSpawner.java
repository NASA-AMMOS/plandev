package gov.nasa.jpl.plandev.foomissionmodel.activities;

import gov.nasa.jpl.plandev.foomissionmodel.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import static gov.nasa.jpl.plandev.foomissionmodel.generated.ActivityActions.call;
import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.delay;

/**
 * An activity that spawns a DaemonCheckerActivity after delaying.
 * Useful for testing the behavior of exceptions thrown by child activities.
 *
 * @param minutesElapsed The expected number of minutes elapsed when the DaemonCheckerActivity begins
 * @param spawnDelay The number of minutes to delay between the start of this activity and spawning the DaemonCheckerActivity
 */
@ActivityType("DaemonCheckerSpawner")
public record DaemonCheckerSpawner(int minutesElapsed, int spawnDelay) {
  @ActivityType.EffectModel
  public void run(final Mission mission) {
    delay(Duration.of(spawnDelay, Duration.MINUTE));
    call(mission, new DaemonCheckerActivity(minutesElapsed));
  }
}
