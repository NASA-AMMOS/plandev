package gov.nasa.ammos.plandev.procedural.scheduling.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Declares Defaults method for instantiation
// Primarily used for Some Optional Parameter types
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface WithDefaults {}
