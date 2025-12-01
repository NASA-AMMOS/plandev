package gov.nasa.jpl.plandev.foomissionmodel.activities;

import gov.nasa.jpl.plandev.foomissionmodel.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.EffectModel;

import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.delay;

@ActivityType("ZeroDurationUncontrollableActivity")
public final class ZeroDurationUncontrollableActivity {
  @EffectModel
  public void run(final Mission mission) {
  }
}
