package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Subsystem;

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
