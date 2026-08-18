package gov.nasa.ammos.plandev.procedural.scheduling.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks the default scheduling procedure whose arguments are all defaulted
// Primarily used for All Optional Parameter types
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Template {}
