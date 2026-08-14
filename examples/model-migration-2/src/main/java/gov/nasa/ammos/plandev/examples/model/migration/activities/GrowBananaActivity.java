package gov.nasa.ammos.plandev.examples.model.migration.activities;

import gov.nasa.ammos.plandev.examples.model.migration.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Template;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;

/**
 * Monke has evolve. Monke now make banana. Monke is farmer.
 *
 * This activity causes a monkey to create new bananas in the banana plant.
 *
 * @subsystem fruit
 * @contact John Doe
 */
@ActivityType("GrowBanana")
public record GrowBananaActivity(int quantity) {

  public static @Template GrowBananaActivity defaults() {
    return new GrowBananaActivity(1);
  }

  @Validation("Quantity must be positive")
  @Validation.Subject("quantity")
  public boolean validateQuantity() {
    return this.quantity() > 0;
  }

  @EffectModel
  public void run(final Mission mission) {
  }
}
