package gov.nasa.jpl.aerie.scheduler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * demarks a potentially null value (method return, argument, field, etc)
 *
 * alas, there is not yet a java-wide standard. this is an interim placeholder
 * until we pick a project-wide solution. ref:
 * https://stackoverflow.com/questions/4963300/which-notnull-java-annotation-should-i-use
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface Nullable {}
