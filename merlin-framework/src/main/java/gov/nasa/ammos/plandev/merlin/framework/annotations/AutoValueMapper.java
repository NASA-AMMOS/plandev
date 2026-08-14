package gov.nasa.jpl.aerie.merlin.framework.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AutoValueMapper {
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.TYPE)
  @interface Record {}

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.ANNOTATION_TYPE)
  @interface Annotation {}
}
