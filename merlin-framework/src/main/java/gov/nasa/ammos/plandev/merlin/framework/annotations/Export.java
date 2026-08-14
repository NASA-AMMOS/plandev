package gov.nasa.jpl.aerie.merlin.framework.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Export {

  // Marks the default activity instance whose arguments are all defaulted
  // Primarily used for All Optional Parameter types
  // Add handling for @target on no-arg constructors and static instances
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.METHOD)
  @interface Template {}

  // Declares Defaults method for instantiation
  // Primarily used for Some Optional Parameter types
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.TYPE)
  @interface WithDefaults {}

  // A parameter to export through the mission-agnostic interface
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.FIELD)
  @interface Parameter {
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.METHOD)
  @interface Validation {
    String value() default "";

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.METHOD)
    @interface Subject {
      String[] value();
    }
  }
}
