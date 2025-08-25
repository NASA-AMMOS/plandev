package gov.nasa.jpl.aerie.merlin.framework.annotations;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Adds a description to an activity type. It looks like @Description("The description goes here") */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
public @interface Description {
  String value();
}
