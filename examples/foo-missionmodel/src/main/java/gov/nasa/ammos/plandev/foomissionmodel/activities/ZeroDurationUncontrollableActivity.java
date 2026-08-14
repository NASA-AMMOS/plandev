package gov.nasa.ammos.plandev.foomissionmodel.activities;

import gov.nasa.ammos.plandev.foomissionmodel.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;

@ActivityType("ZeroDurationUncontrollableActivity")
public final class ZeroDurationUncontrollableActivity {
  @EffectModel
  public void run(final Mission mission) {
  }
}
