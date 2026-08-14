package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

public record Clock(Duration extract) implements Dynamics<Duration, Clock> {
  @Override
  public Clock step(Duration t) {
    return clock(extract().plus(t));
  }

  public static Clock clock(Duration startingTime) {
    return new Clock(startingTime);
  }
}
