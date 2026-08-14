package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Template;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;

/**
 * Throw a banana.
 *
 * This activity causes an entire banana to be chucked by a hyperactive monkey.
 *
 * @contact John Doe
 */
@ActivityType("ThrowBanana")
public record ThrowBananaActivity(double speed) {

  public static @Template ThrowBananaActivity defaults() {
    return new ThrowBananaActivity(1.0);
  }

  @Validation("Speed must be positive")
  @Validation.Subject("speed")
  public boolean validateBiteSize() {
    return this.speed() > 0;
  }

  @EffectModel
  public void run(final Mission mission) {
    mission.plant.add(-1);
  }
}

