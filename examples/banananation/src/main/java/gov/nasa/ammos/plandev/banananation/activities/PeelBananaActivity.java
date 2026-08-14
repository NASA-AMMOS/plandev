package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.contrib.metadata.Unit;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Subsystem;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOURS;

/**
 * Peel a banana, in preparation for consumption.
 *
 * This activity causes a banana to enter the peeled state, allowing
 * it to be bitten later. Peeling from the wrong end will cause some
 * amount of banana to become unbiteable.
 *
 * @contact Jane Doe
 */
@ActivityType("PeelBanana")
@Subsystem("Prepare")
public final class PeelBananaActivity {
  private static final double MASHED_BANANA_AMOUNT = 1.0;

  public enum PeelDirectionEnum {
    fromStem,
    fromTip,
  }

  @Parameter
  @Unit("direction")
  public PeelDirectionEnum peelDirection = PeelDirectionEnum.fromStem;

  @ActivityType.MaximumDuration
  public static final Duration DURATION_UPPER_BOUND = Duration.of(1, HOURS);

  @EffectModel
  public void run(final Mission mission) {
    if (peelDirection.equals(PeelDirectionEnum.fromStem)) {
      mission.fruit.subtract(MASHED_BANANA_AMOUNT);
    }
    mission.peel.subtract(1.0);
  }
}
