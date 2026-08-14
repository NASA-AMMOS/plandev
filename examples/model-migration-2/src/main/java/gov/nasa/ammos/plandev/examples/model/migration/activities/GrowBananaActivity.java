package gov.nasa.jpl.aerie.examples.model.migration.activities;

import gov.nasa.jpl.aerie.examples.model.migration.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Template;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOURS;

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
