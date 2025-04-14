package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.ControllableDuration;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Template;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
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
    final var rate = this.quantity() / (double) Duration.of(2, HOURS).ratioOver(Duration.SECOND);
    mission.fruit.rate.add(rate);
    delay(Duration.of(2, HOURS));
    mission.fruit.rate.add(-rate);
    mission.plant.add(this.quantity());
  }
}
