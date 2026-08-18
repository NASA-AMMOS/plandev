package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Subsystem;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.delay;

/**
 * Monke is patient.
 *
 * Waits two days for bananas to ripen. Ripeness is not modelled.
 *
 * @contact Jane Doe
 */
@ActivityType("RipenBanana")
@Subsystem("Prepare")
public final class RipenBananaActivity {

  @ActivityType.FixedDuration
  public static Duration duration() {
    return Duration.of(48, Duration.HOUR);
  }

  @EffectModel
  public void run(final Mission mission) {
    delay(duration());
  }
}
