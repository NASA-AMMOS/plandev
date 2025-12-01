package gov.nasa.jpl.plandev.foomissionmodel.activities;

import gov.nasa.jpl.plandev.foomissionmodel.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.*;

@ActivityType("BasicActivity")
public final class BasicActivity {
  @EffectModel
  public void run(final Mission mission) {
    delay(Duration.of(2, Duration.SECONDS));
  }
}
