package gov.nasa.jpl.aerie.merlin.framework.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PACKAGE)
public @interface MissionModel {
  Class<?> model();

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @interface WithConfiguration {
    Class<?> value();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @interface AllActivityTypes {
    WithActivityType[] value();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @interface AllSubsystems {
    WithSubsystem[] value();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @interface AllMappers {
    WithMappers[] value();
  }


  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @Repeatable(AllActivityTypes.class)
  @interface WithActivityType {
    Class<?> value();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @Repeatable(AllSubsystems.class)
  @interface WithSubsystem {
    String value();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @Repeatable(AllMappers.class)
  @interface WithMappers {
    Class<?> value();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @Repeatable(AllMetadata.class)
  @interface WithMetadata {
    String name();
    Class<?> annotation();
  }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.PACKAGE)
  @interface AllMetadata {
    WithMetadata[] value();
  }
}
