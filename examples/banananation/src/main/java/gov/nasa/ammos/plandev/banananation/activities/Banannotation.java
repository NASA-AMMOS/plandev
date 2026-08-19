package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.merlin.framework.annotations.AutoValueMapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
// Custom annotations are broken
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE_USE)
@AutoValueMapper.Annotation
public @interface Banannotation {
  String value();
}
