package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

@ActivityType("OtherControllableDurationActivity")
public record OtherControllableDurationActivity(Duration duration) {

  @ActivityType.EffectModel
  @ActivityType.ControllableDuration(parameterName = "duration")
  public void run(final Mission mission) {
    delay(duration);
  }

}
