package gov.nasa.ammos.plandev.examples.model.migration.activities;

import gov.nasa.ammos.plandev.examples.model.migration.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;

/**
 * Pick a banana from the plant.
 */
@ActivityType("PickBanana")
public final class PickBananaActivity {
  @Parameter
  public int quantity = 10;

  @Validation("quantity must be positive")
  @Validation.Subject("quantity")
  public boolean validateQuantity() {
    return this.quantity > 0;
  }

  @EffectModel
  public void run(final Mission mission) {
  }
}
