package gov.nasa.jpl.plandev.banananation.activities;

import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.plandev.merlin.framework.annotations.Export.Validation;

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

  public void run() {
    if (this.throwException) {
      throw new RuntimeException("Throwing runtime exception during runtime");
    }
  }
}
