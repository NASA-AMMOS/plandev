package gov.nasa.jpl.aerie.merlin.processor.metamodel;

import com.squareup.javapoet.ClassName;

import javax.lang.model.element.PackageElement;
import java.util.Objects;

public final class ActivityMapperRecord {
  public final ClassName name;
  public final boolean isCustom;

  public ActivityMapperRecord(final ClassName name, final boolean isCustom) {
    this.name = Objects.requireNonNull(name);
    this.isCustom = isCustom;
  }

  public static ActivityMapperRecord custom(final ClassName name) {
    return new ActivityMapperRecord(name, true);
  }

  public static ActivityMapperRecord
  generatedFor(final ClassName activityTypeName, final PackageElement missionModelElement) {
    final var missionModelPackage = missionModelElement.getQualifiedName().toString();
    final var activityPackage = activityTypeName.packageName();

    final String generatedSuffix;
    if ((activityPackage + ".").startsWith(missionModelPackage + ".")) {
      generatedSuffix = activityPackage.substring(missionModelPackage.length());
    } else {
      generatedSuffix = activityPackage;
    }

    final var mapperName = ClassName.get(
        missionModelPackage + ".generated" + generatedSuffix,
        activityTypeName.simpleName() + "Mapper");

    return new ActivityMapperRecord(mapperName, false);
  }
}
