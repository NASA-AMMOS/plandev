package gov.nasa.jpl.plandev.foomissionmodel.activities;

import gov.nasa.jpl.plandev.foomissionmodel.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.delay;

@ActivityType("ControllableDurationActivity")
public record ControllableDurationActivity(Duration duration) {

  @ActivityType.EffectModel
  @ActivityType.ControllableDuration(parameterName = "duration")
  public void run(final Mission mission) {
    delay(duration);
  }

}
