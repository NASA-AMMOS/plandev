package gov.nasa.jpl.aerie.merlin.processor.metamodel;

import com.squareup.javapoet.ClassName;

import javax.lang.model.element.PackageElement;
import java.util.Objects;

public final class ActivityValueMapperRecord {
  public final ClassName name;
  public final boolean isCustom;

  public ActivityValueMapperRecord(final ClassName name, final boolean isCustom) {
    this.name = Objects.requireNonNull(name);
    this.isCustom = isCustom;
  }

  public static ActivityValueMapperRecord custom(final ClassName name) {
    return new ActivityValueMapperRecord(name, true);
  }

  public static ActivityValueMapperRecord
  generatedFor(final ClassName activityTypeName, final PackageElement missionModelElement) {
    final var missionModelPackage = missionModelElement.getQualifiedName().toString();
    final var activityPackage = activityTypeName.packageName() + "ValueMappers";

    final String generatedSuffix;
    if ((activityPackage + ".").startsWith(missionModelPackage + ".")) {
      generatedSuffix = activityPackage.substring(missionModelPackage.length());
    } else {
      generatedSuffix = activityPackage;
    }

    final var mapperName = ClassName.get(
        missionModelPackage + ".generated" + generatedSuffix,
        activityTypeName.simpleName() + "ValueMapper");

    return new ActivityValueMapperRecord(mapperName, false);
  }
}
