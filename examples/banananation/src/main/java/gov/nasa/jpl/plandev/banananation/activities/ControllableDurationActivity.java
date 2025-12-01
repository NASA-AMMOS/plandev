package gov.nasa.jpl.plandev.banananation.activities;

import gov.nasa.jpl.plandev.banananation.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.delay;

@ActivityType("ControllableDurationActivity")
public record ControllableDurationActivity(Duration duration) {

  @EffectModel
  @ActivityType.ControllableDuration(parameterName = "duration")
  public void run(Mission mission) {
    // Creates a profile segment of at most the given duration
    mission.plant.add(1);
    delay(duration);
    mission.plant.add(-1);
  }
}
