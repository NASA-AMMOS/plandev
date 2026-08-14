package gov.nasa.ammos.plandev.foomissionmodel.activities;

import gov.nasa.ammos.plandev.foomissionmodel.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.*;

@ActivityType("BasicActivity")
public final class BasicActivity {
  @EffectModel
  public void run(final Mission mission) {
    delay(Duration.of(2, Duration.SECONDS));
  }
}
