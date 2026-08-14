package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;

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
