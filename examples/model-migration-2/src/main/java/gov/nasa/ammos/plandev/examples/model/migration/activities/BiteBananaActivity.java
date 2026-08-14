package gov.nasa.ammos.plandev.examples.model.migration.activities;

import gov.nasa.ammos.plandev.examples.model.migration.Flag;
import gov.nasa.ammos.plandev.examples.model.migration.Mission;
import gov.nasa.ammos.plandev.contrib.metadata.Unit;
import gov.nasa.ammos.plandev.contrib.models.ValidationResult;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;

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
    return new ComputedAttributes(bigBiteSize, newFlag);
  }

  @AutoValueMapper.Record
  public record ComputedAttributes(boolean biteSizeWasBig, Flag newFlag) {}
}
