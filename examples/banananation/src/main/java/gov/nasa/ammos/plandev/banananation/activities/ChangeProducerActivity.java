package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Description;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;

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
