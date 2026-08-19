package gov.nasa.ammos.plandev.foomissionmodel.activities;

import gov.nasa.ammos.plandev.foomissionmodel.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.*;

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
