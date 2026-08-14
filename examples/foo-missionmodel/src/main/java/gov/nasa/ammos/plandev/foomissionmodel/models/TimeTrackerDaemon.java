package gov.nasa.jpl.aerie.foomissionmodel.models;

import gov.nasa.jpl.aerie.contrib.models.counters.Counter;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

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
