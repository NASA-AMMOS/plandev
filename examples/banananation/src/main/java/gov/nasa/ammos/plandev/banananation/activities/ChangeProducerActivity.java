package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Description;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;

/**
 * Changes the active banana producer.
 */
@ActivityType("ChangeProducer")
@Description("Changes the producer, the default being Dole")
public final class ChangeProducerActivity {
  @Parameter
  public String producer = "Dole";

  @EffectModel
  public void run(final Mission mission) {
    mission.producer.set(this.producer);
  }
}
