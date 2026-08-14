package gov.nasa.ammos.plandev.examples.model.migration.activities;

import gov.nasa.ammos.plandev.examples.model.migration.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;

/**
 * Changes the active banana producer.
 */
@ActivityType("ChangeProducer")
public final class ChangeProducerActivity {
  @Parameter
  public String producer = "Dole";

  @Parameter
  public String newRequiredParameter;

  @EffectModel
  public void run(final Mission mission) {

  }
}
