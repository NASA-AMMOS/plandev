package gov.nasa.jpl.aerie.merlin.driver.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Documents a variable that is wholly derived from upstream data. */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.LOCAL_VARIABLE})
public @interface DerivedFrom {
  /**
   * Describes where the variable is derived from in a human-readable form.
   *
   * <p>
   * May contain the names of other fields, or more vague descriptions of upstream data sources.
   * </p>
   */
  String[] value();
}
