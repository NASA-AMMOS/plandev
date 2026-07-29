package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.*;

/**
 * Nap time [banana style]!!!!
 * This activity has no effect :)
 *
 * @contact Jane Doe
 */
@ActivityType("BananaNap")
public final class BananaNapActivity {
  @ActivityType.FixedDuration
  public static final Duration DURATION = Duration.HOUR;

  @EffectModel
  public void run(final Mission mission) {
    delay(DURATION);
  }
}
