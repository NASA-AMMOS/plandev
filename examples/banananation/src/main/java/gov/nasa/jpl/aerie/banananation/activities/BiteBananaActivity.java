package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Flag;
import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.contrib.metadata.Unit;
import gov.nasa.jpl.aerie.contrib.models.ValidationResult;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;

/**
 * Bite a banana.
 *
 * This activity causes a piece of banana to be bitten off and consumed.
 *
 * @subsystem fruit
 * @contact John Doe
 */
@ActivityType("BiteBanana")
public final class BiteBananaActivity {
  @Parameter

  @Banannotation("Specifies the size of bite to take")
  @Unit("m")
  public double biteSize = 1.0;

  @Parameter
  public String newOptionalParameter = "";

  @Validation
  public ValidationResult validateBiteSize() {
    return new ValidationResult(this.biteSize > 0, "biteSize", "bite size must be positive");
  }

  @EffectModel
  public ComputedAttributes run(final Mission mission) {
    final var bigBiteSize = biteSize > 1.0;
    final var newFlag = bigBiteSize ? Flag.B : Flag.A;
    mission.flag.set(newFlag);
    mission.fruit.subtract(biteSize);
    return new ComputedAttributes(bigBiteSize, newFlag);
  }

  @AutoValueMapper.Record
  public record ComputedAttributes(boolean biteSizeWasBig, Flag newFlag) {}
}
