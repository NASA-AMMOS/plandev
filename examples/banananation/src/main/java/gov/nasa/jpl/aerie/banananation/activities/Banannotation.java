package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

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
