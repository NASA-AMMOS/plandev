package gov.nasa.ammos.plandev.foomissionmodel.activities;

import gov.nasa.ammos.plandev.foomissionmodel.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.delay;

@ActivityType("OtherControllableDurationActivity")
public record OtherControllableDurationActivity(Duration duration) {

  @ActivityType.EffectModel
  @ActivityType.ControllableDuration(parameterName = "duration")
  public void run(final Mission mission) {
    delay(duration);
  }

}
