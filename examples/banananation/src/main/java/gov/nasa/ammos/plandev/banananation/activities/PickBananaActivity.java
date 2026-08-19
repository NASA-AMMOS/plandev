package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Subsystem;

/**
 * Pick a banana from the plant.
 */
@ActivityType("PickBanana")
@Subsystem("Pick")
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
    mission.plant.add(-quantity);
  }
}
