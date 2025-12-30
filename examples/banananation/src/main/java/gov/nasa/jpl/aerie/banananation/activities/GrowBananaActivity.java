package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.ControllableDuration;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Template;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;

/**
 * Monke has evolve. Monke now make banana. Monke is farmer.
 *
 * This activity causes a monkey to create new bananas in the banana plant.
 *
 * @contact John Doe
 */
@ActivityType("GrowBanana")
public record GrowBananaActivity(int quantity, Duration growingDuration) {

  public static @Template GrowBananaActivity defaults() {
    return new GrowBananaActivity(1, Duration.of(1, Duration.HOUR));
  }

  @Validation("Quantity must be positive")
  @Validation.Subject("quantity")
  public boolean validateQuantity() {
    return this.quantity() > 0;
  }

  @Validation("Growing Duration must be positive")
  @Validation.Subject("growingDuration")
  public boolean validateGrowingDuration() {
    return this.growingDuration().longerThan(Duration.ZERO);
  }

  @EffectModel
  @ControllableDuration(parameterName = "growingDuration")
  public void run(final Mission mission) {
    final var rate = this.quantity() / (double) this.growingDuration().ratioOver(Duration.SECOND);
    mission.fruit.rate.add(rate);
    delay(this.growingDuration());
    mission.fruit.rate.add(-rate);
    mission.plant.add(this.quantity());
  }
}
