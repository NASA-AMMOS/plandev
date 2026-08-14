package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;

/**
 * Conditionally throws a runtime exception at both validation time and runtime
 */
@ActivityType("ExceptionActivity")
public final class ExceptionActivity {
  @Parameter
  public boolean throwException = false;

  @Validation("Throws an exception if set")
  @Validation.Subject("throwException")
  public boolean conditionallyThrowException() {
    if (this.throwException) {
      throw new RuntimeException("Throwing runtime exception during validation");
    }
    return true;
  }

  @EffectModel
  public void run(final Mission mission) {
    if (this.throwException) {
      throw new RuntimeException("Throwing runtime exception during runtime");
    }
  }
}
