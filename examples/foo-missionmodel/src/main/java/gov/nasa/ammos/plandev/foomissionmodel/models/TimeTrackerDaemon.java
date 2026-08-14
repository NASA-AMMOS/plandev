package gov.nasa.ammos.plandev.foomissionmodel.models;

import gov.nasa.ammos.plandev.contrib.models.counters.Counter;
import gov.nasa.ammos.plandev.merlin.framework.ModelActions;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

/**
 * A daemon task that tracks the number of minutes since plan start
 */
public class TimeTrackerDaemon {
  private final Counter<Integer> minutesElapsed;

  public int getMinutesElapsed() {
    return minutesElapsed.get();
  }

  public TimeTrackerDaemon(){ minutesElapsed = Counter.ofInteger(0);}

  public void run(){
    minutesElapsed.add(-minutesElapsed.get());
    while(true) {
      ModelActions.delay(Duration.MINUTE);
      minutesElapsed.add(1);
    }
  }

}
