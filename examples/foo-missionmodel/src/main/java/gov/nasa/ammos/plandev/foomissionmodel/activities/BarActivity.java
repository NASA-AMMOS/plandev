package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;

@ActivityType("bar")
public final class BarActivity {
  @EffectModel
  public void run(final Mission mission) {
    System.out.println("1-start");
    call(() -> {
      System.out.println("2-start");
      spawn(() -> {
        System.out.println("3-start");
        delay(1, Duration.SECOND);
        System.out.println("3-end");
      });
      System.out.println("2-end");
    });
    System.out.println("1-end");
  }
}
